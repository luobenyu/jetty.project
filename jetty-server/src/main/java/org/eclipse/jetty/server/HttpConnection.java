//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BlockingCallback;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A {@link Connection} that handles the HTTP protocol.</p>
 */
public class HttpConnection extends AbstractConnection implements Runnable, HttpTransport
{
    public static final String UPGRADE_CONNECTION_ATTRIBUTE = "org.eclipse.jetty.server.HttpConnection.UPGRADE";
    private static final boolean REQUEST_BUFFER_DIRECT=false;
    private static final boolean HEADER_BUFFER_DIRECT=false;
    private static final boolean CHUNK_BUFFER_DIRECT=false;
    private static final Logger LOG = Log.getLogger(HttpConnection.class);
    private static final ThreadLocal<HttpConnection> __currentConnection = new ThreadLocal<>();

    private final HttpConfiguration _config;
    private final Connector _connector;
    private final ByteBufferPool _bufferPool;
    private final HttpGenerator _generator;
    private final HttpChannelOverHttp _channel;
    private final HttpParser _parser;
    private volatile ByteBuffer _requestBuffer = null;
    private volatile ByteBuffer _chunk = null;
    private BlockingCallback _readBlocker = new BlockingCallback();
    private BlockingCallback _writeBlocker = new BlockingCallback();


    public static HttpConnection getCurrentConnection()
    {
        return __currentConnection.get();
    }

    protected static void setCurrentConnection(HttpConnection connection)
    {
        __currentConnection.set(connection);
    }

    public HttpConfiguration getHttpConfiguration()
    {
        return _config;
    }

    public HttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
    {
        // Tell AbstractConnector executeOnFillable==true because we want the same thread that
        // does the HTTP parsing to handle the request so its cache is hot
        super(endPoint, connector.getExecutor(),true);

        _config = config;
        _connector = connector;
        _bufferPool = _connector.getByteBufferPool();
        _generator = new HttpGenerator();
        _generator.setSendServerVersion(_config.getSendServerVersion());
        _channel = new HttpChannelOverHttp(connector, config, endPoint, this, new Input());
        _parser = newHttpParser();

        LOG.debug("New HTTP Connection {}", this);
    }

    protected HttpParser newHttpParser()
    {
        return new HttpParser(newRequestHandler(), getHttpConfiguration().getRequestHeaderSize());
    }

    protected HttpParser.RequestHandler<ByteBuffer> newRequestHandler()
    {
        return _channel;
    }

    public Server getServer()
    {
        return _connector.getServer();
    }

    public Connector getConnector()
    {
        return _connector;
    }

    public HttpChannel<?> getHttpChannel()
    {
        return _channel;
    }

    public void reset()
    {
        // If we are still expecting
        if (_channel.isExpecting100Continue())
        {
            // reset to avoid seeking remaining content
            _parser.reset();
            // close to seek EOF
            _parser.close();
        }
        // else if we are persistent
        else if (_generator.isPersistent())
            // reset to seek next request
            _parser.reset();
        else
            // else seek EOF
            _parser.close();

        _generator.reset();
        _channel.reset();

        releaseRequestBuffer();
        if (_chunk!=null)
        {
            _bufferPool.release(_chunk);
            _chunk=null;
        }
    }


    @Override
    public int getMessagesIn()
    {
        return getHttpChannel().getRequests();
    }

    @Override
    public int getMessagesOut()
    {
        return getHttpChannel().getRequests();
    }

    @Override
    public String toString()
    {
        return String.format("%s,g=%s,p=%s",
                super.toString(),
                _generator,
                _parser);
    }

    private void releaseRequestBuffer()
    {
        if (_requestBuffer != null && !_requestBuffer.hasRemaining())
        {
            ByteBuffer buffer=_requestBuffer;
            _requestBuffer=null;
            _bufferPool.release(buffer);
        }
    }

    /**
     * <p>Parses and handles HTTP messages.</p>
     * <p>This method is called when this {@link Connection} is ready to read bytes from the {@link EndPoint}.
     * However, it can also be called if there is unconsumed data in the _requestBuffer, as a result of
     * resuming a suspended request when there is a pipelined request already read into the buffer.</p>
     * <p>This method fills bytes and parses them until either: EOF is filled; 0 bytes are filled;
     * the HttpChannel finishes handling; or the connection has changed.</p>
     */
    @Override
    public void onFillable()
    {
        LOG.debug("{} onFillable {}", this, _channel.getState());

        setCurrentConnection(this);
        try
        {
            while (true)
            {
                // Can the parser progress (even with an empty buffer)
                boolean call_channel=_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer);

                // Parse the buffer
                if (call_channel)
                {
                    // Parse as much content as there is available before calling the channel
                    // this is both efficient (may queue many chunks), will correctly set available for 100 continues
                    // and will drive the parser to completion if all content is available.
                    while (_parser.inContentState())
                    {
                        if (!_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer))
                            break;
                    }

                    // The parser returned true, which indicates the channel is ready to handle a request.
                    // Call the channel and this will either handle the request/response to completion OR,
                    // if the request suspends, the request/response will be incomplete so the outer loop will exit.
                    boolean handle=_channel.handle();

                    // Return if suspended or upgraded
                    if (!handle || getEndPoint().getConnection()!=this)
                        return;
                }
                else if (BufferUtil.isEmpty(_requestBuffer))
                {
                    if (_requestBuffer == null)
                        _requestBuffer = _bufferPool.acquire(getInputBufferSize(), REQUEST_BUFFER_DIRECT);

                    int filled = getEndPoint().fill(_requestBuffer);
                    if (filled==0) // Do a retry on fill 0 (optimisation for SSL connections)
                        filled = getEndPoint().fill(_requestBuffer);

                    LOG.debug("{} filled {}", this, filled);

                    // If we failed to fill
                    if (filled == 0)
                    {
                        // Somebody wanted to read, we didn't so schedule another attempt
                        releaseRequestBuffer();
                        fillInterested();
                        return;
                    }
                    else if (filled < 0)
                    {
                        _parser.shutdownInput();
                        // We were only filling if fully consumed, so if we have
                        // read -1 then we have nothing to parse and thus nothing that
                        // will generate a response.  If we had a suspended request pending
                        // a response or a request waiting in the buffer, we would not be here.
                        if (getEndPoint().isOutputShutdown())
                            getEndPoint().close();
                        else
                            getEndPoint().shutdownOutput();
                        // buffer must be empty and the channel must be idle, so we can release.
                        releaseRequestBuffer();
                        return;
                    }
                }
                else
                {
                    // TODO work out how we can get here and a better way to handle it
                    LOG.warn("Unexpected state: "+this+ " "+_channel+" "+_channel.getRequest());
                    if (!_channel.getState().isSuspended())
                        getEndPoint().close();
                    return;
                }
            }
        }
        catch (EofException e)
        {
            LOG.debug(e);
        }
        catch (Exception e)
        {
            if (_parser.isIdle())
                LOG.debug(e);
            else
                LOG.warn(this.toString(), e);
            close();
        }
        finally
        {
            setCurrentConnection(null);
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void run()
    {
        onFillable();
    }

    @Override
    public void send(HttpGenerator.ResponseInfo info, ByteBuffer content, boolean lastContent) throws IOException
    {
        try
        {
            if (info==null)
                new ContentCallback(content,lastContent,_writeBlocker).iterate();
            else
            {
                // If we are still expecting a 100 continues
                if (_channel.isExpecting100Continue())
                    // then we can't be persistent
                    _generator.setPersistent(false);
                new CommitCallback(info,content,lastContent,_writeBlocker).iterate();
            }
            _writeBlocker.block();
        }
        catch (ClosedChannelException e)
        {
            throw new EofException(e);
        }
        catch (IOException e)
        {
            throw e;
        }
    }

    @Override
    public void send(ResponseInfo info, ByteBuffer content, boolean lastContent, Callback callback)
    {
        if (info==null)
            new ContentCallback(content,lastContent,callback).iterate();
        else
        {
            // If we are still expecting a 100 continues
            if (_channel.isExpecting100Continue())
                // then we can't be persistent
                _generator.setPersistent(false);
            new CommitCallback(info,content,lastContent,callback).iterate();
        }
    }

    @Override
    public void send(ByteBuffer content, boolean lastContent, Callback callback)
    {
        new ContentCallback(content,lastContent,callback).iterate();
    }

    @Override
    public void completed()
    {
        // Finish consuming the request
        if (_parser.isInContent() && _generator.isPersistent() && !_channel.isExpecting100Continue())
            // Complete reading the request
            _channel.getRequest().getHttpInput().consumeAll();

        // Handle connection upgrades
        if (_channel.getResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            Connection connection = (Connection)_channel.getRequest().getAttribute(UPGRADE_CONNECTION_ATTRIBUTE);
            if (connection != null)
            {
                LOG.debug("Upgrade from {} to {}", this, connection);
                onClose();
                getEndPoint().setConnection(connection);
                connection.onOpen();
                reset();
                return;
            }
        }

        reset();

        // if we are not called from the onfillable thread, schedule completion
        if (getCurrentConnection()!=this)
        {
            if (_parser.isStart())
            {
                // it wants to eat more
                if (_requestBuffer == null)
                {
                    fillInterested();
                }
                else if (getConnector().isStarted())
                {
                    LOG.debug("{} pipelined", this);

                    try
                    {
                        getExecutor().execute(this);
                    }
                    catch (RejectedExecutionException e)
                    {
                        if (getConnector().isStarted())
                            LOG.warn(e);
                        else
                            LOG.ignore(e);
                        getEndPoint().close();
                    }
                }
                else
                {
                    getEndPoint().close();
                }
            }
        }
    }

    public ByteBuffer getRequestBuffer()
    {
        return _requestBuffer;
    }

    private class Input extends ByteBufferHttpInput
    {
        @Override
        protected void blockForContent() throws IOException
        {
            /* We extend the blockForContent method to replace the
            default implementation of a blocking queue with an implementation
            that uses the calling thread to block on a readable callback and
            then to do the parsing before before attempting the read.
             */
            while (!_parser.isComplete())
            {
                // Can the parser progress (even with an empty buffer)
                boolean event=_parser.parseNext(_requestBuffer==null?BufferUtil.EMPTY_BUFFER:_requestBuffer);

                // If there is more content to parse, loop so we can queue all content from this buffer now without the
                // need to call blockForContent again
                while (!event && BufferUtil.hasContent(_requestBuffer) && _parser.inContentState())
                    event=_parser.parseNext(_requestBuffer);

                // If we have content, return
                if (_parser.isComplete() || available()>0)
                    return;

                // Do we have content ready to parse?
                if (BufferUtil.isEmpty(_requestBuffer))
                {
                    // If no more input
                    if (getEndPoint().isInputShutdown())
                    {
                        _parser.shutdownInput();
                        shutdown();
                        return;
                    }

                    // Wait until we can read
                    block(_readBlocker);
                    LOG.debug("{} block readable on {}",this,_readBlocker);
                    _readBlocker.block();

                    // We will need a buffer to read into
                    if (_requestBuffer==null)
                    {
                        long content_length=_channel.getRequest().getContentLength();
                        int size=getInputBufferSize();
                        if (size<content_length)
                            size=size*4; // TODO tune this
                        _requestBuffer=_bufferPool.acquire(size,REQUEST_BUFFER_DIRECT);
                    }

                    // read some data
                    int filled=getEndPoint().fill(_requestBuffer);
                    LOG.debug("{} block filled {}",this,filled);
                    if (filled<0)
                    {
                        _parser.shutdownInput();
                        return;
                    }
                }
            }
        }

        @Override
        protected void onContentQueued(ByteBuffer ref)
        {
            /* This callback could be used to tell the connection
             * that the request did contain content and thus the request
             * buffer needs to be held until a call to #onAllContentConsumed
             *
             * However it turns out that nothing is needed here because either a
             * request will have content, in which case the request buffer will be
             * released by a call to onAllContentConsumed; or it will not have content.
             * If it does not have content, either it will complete quickly and the
             * buffers will be released in completed() or it will be suspended and
             * onReadable() contains explicit handling to release if it is suspended.
             *
             * We extend this method anyway, to turn off the notify done by the
             * default implementation as this is not needed by our implementation
             * of blockForContent
             */
        }

        @Override
        protected void onAllContentConsumed()
        {
            /* This callback tells the connection that all content that has
             * been parsed has been consumed. Thus the request buffer may be
             * released if it is empty.
             */
            releaseRequestBuffer();
        }

        @Override
        public String toString()
        {
            return super.toString()+"{"+_channel+","+HttpConnection.this+"}";
        }
    }

    private class HttpChannelOverHttp extends HttpChannel<ByteBuffer>
    {
        public HttpChannelOverHttp(Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
        {
            super(connector,config,endPoint,transport,input);
        }

        @Override
        public void badMessage(int status, String reason)
        {
            _generator.setPersistent(false);
            super.badMessage(status,reason);
        }

        @Override
        public boolean headerComplete()
        {
            boolean persistent;
            HttpVersion version = getHttpVersion();

            switch (version)
            {
                case HTTP_0_9:
                {
                    persistent = false;
                    break;
                }
                case HTTP_1_0:
                {
                    persistent = getRequest().getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                    if (!persistent)
                        persistent = HttpMethod.CONNECT.is(getRequest().getMethod());
                    if (persistent)
                        getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);
                    break;
                }
                case HTTP_1_1:
                {
                    persistent = !getRequest().getHttpFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString());
                    if (!persistent)
                        persistent = HttpMethod.CONNECT.is(getRequest().getMethod());
                    if (!persistent)
                        getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }

            if (!persistent)
                _generator.setPersistent(false);

            return super.headerComplete();
        }

        @Override
        protected void handleException(Throwable x)
        {
            _generator.setPersistent(false);
            super.handleException(x);
        }
    }

    private class CommitCallback extends IteratingNestedCallback
    {
        final ByteBuffer _content;
        final boolean _lastContent;
        final ResponseInfo _info;
        ByteBuffer _header;

        CommitCallback(ResponseInfo info, ByteBuffer content, boolean last, Callback callback)
        {
            super(callback);
            _info=info;
            _content=content;
            _lastContent=last;
        }

        @Override
        public boolean process() throws Exception
        {
            ByteBuffer chunk = _chunk;
            while (true)
            {
                HttpGenerator.Result result = _generator.generateResponse(_info, _header, chunk, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} generate: {} ({},{},{})@{}",
                        this,
                        result,
                        BufferUtil.toSummaryString(_header),
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_HEADER:
                    {
                        if (_lastContent && _content!=null && BufferUtil.space(_content)>_config.getResponseHeaderSize() && _content.hasArray() )
                        {
                            // use spare space in content buffer for header buffer
                            int p=_content.position();
                            int l=_content.limit();
                            _content.position(l);
                            _content.limit(l+_config.getResponseHeaderSize());
                            _header=_content.slice();
                            _header.limit(0);
                            _content.position(p);
                            _content.limit(l);
                        }
                        else
                            _header = _bufferPool.acquire(_config.getResponseHeaderSize(), HEADER_BUFFER_DIRECT);
                        continue;
                    }
                    case NEED_CHUNK:
                    {
                        chunk = _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, CHUNK_BUFFER_DIRECT);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response
                        if (_channel.getRequest().isHead())
                        {
                            BufferUtil.clear(chunk);
                            BufferUtil.clear(_content);
                        }

                        // If we have a header
                        if (BufferUtil.hasContent(_header))
                        {
                            if (BufferUtil.hasContent(_content))
                            {
                                if (BufferUtil.hasContent(chunk))
                                    getEndPoint().write(this, _header, chunk, _content);
                                else
                                    getEndPoint().write(this, _header, _content);
                            }
                            else
                                getEndPoint().write(this, _header);
                        }
                        else if (BufferUtil.hasContent(chunk))
                        {
                            if (BufferUtil.hasContent(_content))
                                getEndPoint().write(this, chunk, _content);
                            else
                                getEndPoint().write(this, chunk);
                        }
                        else if (BufferUtil.hasContent(_content))
                        {
                            getEndPoint().write(this, _content);
                        }
                        else
                            continue;
                        return false;
                    }
                    case SHUTDOWN_OUT:
                    {
                        getEndPoint().shutdownOutput();
                        continue;
                    }
                    case DONE:
                    {
                        if (_header!=null)
                        {
                            // don't release header in spare content buffer
                            if (!_lastContent || _content==null || !_content.hasArray() || !_header.hasArray() ||  _content.array()!=_header.array())
                                _bufferPool.release(_header);
                        }
                        return true;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException("generateResponse="+result);
                    }
                }
            }
        }
    }

    private class ContentCallback extends IteratingNestedCallback
    {
        final ByteBuffer _content;
        final boolean _lastContent;

        ContentCallback(ByteBuffer content, boolean last, Callback callback)
        {
            super(callback);
            _content=content;
            _lastContent=last;
        }

        @Override
        public boolean process() throws Exception
        {
            ByteBuffer chunk = _chunk;
            while (true)
            {
                HttpGenerator.Result result = _generator.generateResponse(null, null, chunk, _content, _lastContent);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} generate: {} ({},{})@{}",
                        this,
                        result,
                        BufferUtil.toSummaryString(_content),
                        _lastContent,
                        _generator.getState());

                switch (result)
                {
                    case NEED_HEADER:
                        throw new IllegalStateException();
                    case NEED_CHUNK:
                    {
                        chunk = _chunk = _bufferPool.acquire(HttpGenerator.CHUNK_SIZE, CHUNK_BUFFER_DIRECT);
                        continue;
                    }
                    case FLUSH:
                    {
                        // Don't write the chunk or the content if this is a HEAD response
                        if (_channel.getRequest().isHead())
                        {
                            BufferUtil.clear(chunk);
                            BufferUtil.clear(_content);
                            continue;
                        }
                        else if (BufferUtil.hasContent(chunk))
                        {
                            if (BufferUtil.hasContent(_content))
                                getEndPoint().write(this, chunk, _content);
                            else
                                getEndPoint().write(this, chunk);
                        }
                        else if (BufferUtil.hasContent(_content))
                        {
                            getEndPoint().write(this, _content);
                        }
                        else
                            continue;
                        return false;
                    }
                    case SHUTDOWN_OUT:
                    {
                        getEndPoint().shutdownOutput();
                        continue;
                    }
                    case DONE:
                    {
                        return true;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException("generateResponse="+result);
                    }
                }
            }
        }
    }
}
