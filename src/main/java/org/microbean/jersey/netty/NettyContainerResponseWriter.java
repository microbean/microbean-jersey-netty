/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019 microBean™.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jersey.netty;

import java.io.IOException;
import java.io.OutputStream;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.handler.stream.ChunkedInput;

import io.netty.util.concurrent.EventExecutor;

import org.glassfish.jersey.internal.inject.InjectionManager;

import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

import org.glassfish.jersey.spi.ScheduledExecutorServiceProvider;

public class NettyContainerResponseWriter extends OutputStream implements ChunkedInput<ByteBuf>, ContainerResponseWriter {

  private final ChannelHandlerContext channelHandlerContext;

  private final HttpRequest httpRequest;

  private final InjectionManager injectionManager;

  private volatile ScheduledFuture<?> suspendTimeoutFuture;

  private volatile Runnable suspendTimeoutHandler;

  private volatile boolean closed;

  private volatile boolean responseWritten;

  private volatile ByteBuf byteBuf;

  public NettyContainerResponseWriter(final HttpRequest httpRequest,
                                      final ChannelHandlerContext channelHandlerContext,
                                      final InjectionManager injectionManager) {
    super();
    this.httpRequest = Objects.requireNonNull(httpRequest);
    this.channelHandlerContext = Objects.requireNonNull(channelHandlerContext);
    this.injectionManager = Objects.requireNonNull(injectionManager);
  }


  /*
   * ContainerResponseWriter overrides.  Used only by Jersey, and on a
   * non-Netty-EventLoop-affiliated thread.
   */


  @Override
  public OutputStream writeResponseStatusAndHeaders(final long contentLength, final ContainerResponse containerResponse) throws ContainerException {
    Objects.requireNonNull(containerResponse);
    assert !this.inEventLoop();
    
    final boolean closed = this.closed;
    if (closed) {
      throw new IllegalStateException("closed");
    }

    final OutputStream returnValue;

    // Jersey's native Netty support does this.
    final boolean responseWritten = this.responseWritten;
    if (responseWritten) {
      returnValue = null;
    } else {
      this.responseWritten = true;

      final String reasonPhrase = containerResponse.getStatusInfo().getReasonPhrase();
      final int statusCode = containerResponse.getStatus();
      final HttpResponseStatus status = reasonPhrase == null ? HttpResponseStatus.valueOf(statusCode) : new HttpResponseStatus(statusCode, reasonPhrase);

      final HttpResponse httpResponse;
      if (contentLength == 0) {
        httpResponse = new DefaultFullHttpResponse(this.httpRequest.protocolVersion(), status);
      } else {
        httpResponse = new DefaultHttpResponse(this.httpRequest.protocolVersion(), status);
      }

      final HttpHeaders headers = httpResponse.headers();

      if (contentLength < 0) {
        HttpUtil.setTransferEncodingChunked(httpResponse, true);
      } else if (contentLength != 0) {
        headers.set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
      }

      final Map<? extends String, ? extends List<String>> containerResponseHeaders = containerResponse.getStringHeaders();
      if (containerResponseHeaders != null && !containerResponseHeaders.isEmpty()) {
        final Collection<? extends Entry<? extends String, ? extends List<String>>> entrySet = containerResponseHeaders.entrySet();
        if (entrySet != null && !entrySet.isEmpty()) {
          for (final Entry<? extends String, ? extends List<String>> entry : entrySet) {
            if (entry != null) {
              headers.add(entry.getKey(), entry.getValue());
            }
          }
        }
      }

      if (HttpUtil.isKeepAlive(this.httpRequest)) {
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      }

      // Write the status and headers.  We don't write the entity
      // body, because that's done by Jersey end-user classes; we do
      // however return the OutputStream that they'll ultimately write
      // to.
      //
      // Also see
      // https://github.com/netty/netty/blob/ec69da9afb8388c9ff7e25b2a6bc78c9bf91fb07/transport/src/main/java/io/netty/channel/AbstractChannelHandlerContext.java#L770-L808
      // Seems to handle whether you're on the event loop or not, so
      // writing will occur on the event loop.
      channelHandlerContext.writeAndFlush(httpResponse);

      if (contentLength == 0 || HttpMethod.HEAD.equals(this.httpRequest.method())) {
        channelHandlerContext.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        returnValue = null;
      } else {
        assert this.byteBuf == null;
        final ByteBuf byteBuf = this.channelHandlerContext.alloc().ioBuffer();
        assert byteBuf != null;
        this.byteBuf = byteBuf;
        returnValue = this;
      }
    }
    return returnValue;
  }

  @Override
  public final boolean suspend(final long timeout, final TimeUnit timeUnit, final TimeoutHandler timeoutHandler) {
    assert !this.inEventLoop();
    
    // Lifted from Jersey's supplied Netty integration, with repairs.
    final boolean returnValue;
    if (this.suspendTimeoutHandler != null) {
      returnValue = false;
    } else {
      this.suspendTimeoutHandler = () -> {
        timeoutHandler.onTimeout(this);
        this.suspendTimeoutHandler = null;
      };
      if (timeout > 0) {
        this.suspendTimeoutFuture =
          this.injectionManager.getInstance(ScheduledExecutorServiceProvider.class).getExecutorService().schedule(this.suspendTimeoutHandler, timeout, timeUnit);
      }
      returnValue = true;
    }
    return returnValue;
  }

  @Override
  public final void setSuspendTimeout(final long timeout, final TimeUnit timeUnit) {
    assert !this.inEventLoop();
    
    // Lifted from Jersey's supplied Netty integration, with repairs.
    if (this.suspendTimeoutHandler == null) {
      throw new IllegalStateException();
    }
    if (this.suspendTimeoutFuture != null) {
      this.suspendTimeoutFuture.cancel(true);
    }
    if (timeout > 0) {
      this.suspendTimeoutFuture =
        this.injectionManager.getInstance(ScheduledExecutorServiceProvider.class).getExecutorService().schedule(this.suspendTimeoutHandler, timeout, timeUnit);
    }
  }

  @Override
  public final void commit() {
    assert !this.inEventLoop();
    
    // The flush() method arranges for the actual flushing to take
    // place on the event loop.
    this.channelHandlerContext.flush();

    // Not sure about this.  I think this is right.
    this.close();
  }

  @Override
  public final void failure(final Throwable throwable) {
    assert !this.inEventLoop();

    // Lifted from Jersey's relatively lousy Netty integration; it
    // appears that throwable is simply ignored.  That's not right,
    // surely.

    // The Jersey Grizzly integration rethrows the error as a
    // ContainerException; see
    // https://github.com/eclipse-ee4j/jersey/blob/e2ee2e2d6da4dbb3a4c0f5417c621f402d792280/containers/grizzly2-http/src/main/java/org/glassfish/jersey/grizzly2/httpserver/GrizzlyHttpContainer.java#L279-L300
    // which references JAX-RS specification section 3.3.4, which
    // says, in items number 3 and 4: "Unchecked exceptions and errors
    // that have not been mapped MUST be re-thrown and allowed to
    // propagate to the underlying container." and "Checked exceptions
    // and throwables that have not been mapped and cannot be thrown
    // directly MUST be wrapped in a container-specific exception that
    // is then thrown and allowed to propagate to the underlying
    // container."  We'll follow suit.

    try {
      this.channelHandlerContext.writeAndFlush(new DefaultFullHttpResponse(this.httpRequest.protocolVersion(),
                                                                           HttpResponseStatus.INTERNAL_SERVER_ERROR)).addListener(ChannelFutureListener.CLOSE);
    } catch (final RuntimeException suppressMe) {
      throwable.addSuppressed(suppressMe);
    }
    try {
      this.close();
    } catch (final RuntimeException suppressMe) {
      throwable.addSuppressed(suppressMe);
    }
    if (throwable instanceof RuntimeException) {
      throw (RuntimeException)throwable;
    } else {
      throw new ContainerException(throwable.getMessage(), throwable);
    }
  }

  @Override
  public boolean enableResponseBuffering() {
    assert !this.inEventLoop();
    
    return true;
  }


  /*
   * ChunkedInput<ByteBuf> overrides.  Used in typical scenarios only
   * by HttpChunkedInput, which accepts a ChunkedInput<ByteBuf>.  All
   * overrides must run in the event loop.
   */


  @Override
  public boolean isEndOfInput() throws Exception {
    // It is never the end of the input until the writer is closed.
    // This is because the underlying ByteBuf can grow at will, and
    // can be written to at will.
    assert this.inEventLoop();
    return this.closed;
  }

  @Deprecated
  @Override
  public final ByteBuf readChunk(final ChannelHandlerContext channelHandlerContext) throws Exception {
    return this.readChunk(channelHandlerContext.alloc());
  }

  @Override
  public ByteBuf readChunk(final ByteBufAllocator ignoredByteBufAllocator) throws Exception {
    assert this.inEventLoop();
    assert this.byteBuf != null;
    return this.closed ? null : this.byteBuf.readSlice(this.byteBuf.readableBytes()).asReadOnly();
  }

  @Override
  public final long length() {
    assert this.inEventLoop();
    // It is undocumented but ChunkedNioStream returns -1 to indicate,
    // apparently, no idea of the length.  We don't have any idea
    // either, or at any rate, it could be changing.
    return -1;
  }

  @Override
  public final long progress() {
    assert this.inEventLoop();
    assert this.byteBuf != null;
    // e.g. we've read <progress> of <length> bytes.  Other
    // ChunkedInput implementations return a valid number here even
    // when length() returns -1, so we do too.
    return this.byteBuf.readerIndex();
  }


  /*
   * OutputStream overrides.  Used only by Jersey, specifically by
   * end-user code wishing to write an entity body "back" to the
   * caller.  Returned as an OutputStream by
   * writeResponseStatusAndHeaders() above.  In each of the overrides
   * that follows, this.byteBuf will be non-null.  Every operation
   * below needs to ensure that if calls this.byteBuf.writeXXX(), it
   * does so on the Netty EventLoop.
   */


  @Override
  public final void close() {
    // Remember: this could be ChunkedInput#close() or
    // OutputStream#close().  But ChunkedInput#close() will only be
    // called after endOfInput() returns true.  In this
    // implementation, endOfInput() returns false until closing
    // happens, which means this is unequivocally
    // OutputStream#close().  So it is invoked by Jersey, not Netty.
    // (I hope.)
    this.closed = true;
    final ByteBuf byteBuf = this.byteBuf;
    if (byteBuf != null) {
      // TODO: Do we need to run this on the event loop?
      byteBuf.release();
      this.byteBuf = null;
    }
    // (Actually doesn't do anything; OutputStream#close() is a no-op.
    // super.close();
  }

  @Override
  public final void write(final byte[] bytes) throws IOException {
    if (this.closed) {
      throw new IOException("Closed");
    }
    assert !this.inEventLoop();
    this.write(new Callable<Void>() {
        @Override
        public final Void call() throws IOException {
          assert inEventLoop();
          if (closed) {
            throw new IOException("closed");
          }
          assert byteBuf != null;
          byteBuf.writeBytes(bytes);
          return null;
        }
      });
  }


  @Override
  public final void write(final byte[] bytes, final int offset, final int length) throws IOException {
    if (this.closed) {
      throw new IOException("Closed");
    }
    assert !this.inEventLoop();
    this.write(new Callable<Void>() {
        @Override
        public final Void call() throws IOException {
          assert inEventLoop();
          if (closed) {
            throw new IOException("closed");
          }
          assert byteBuf != null;
          byteBuf.writeBytes(bytes, offset, length);
          return null;
        }
      });
  }

  @Override
  public final void write(final int b) throws IOException {
    if (this.closed) {
      throw new IOException("Closed");
    }
    assert !this.inEventLoop();
    this.write(new Callable<Void>() {
        @Override
        public final Void call() throws IOException {
          assert inEventLoop();
          if (closed) {
            throw new IOException("closed");
          }
          assert byteBuf != null;
          byteBuf.writeByte(b);
          return null;
        }
      });
  }


  /*
   * Utility methods.
   */


  private final boolean inEventLoop() {
    return this.channelHandlerContext.executor().inEventLoop();
  }

  private final void write(final Callable<? extends Void> callable) throws IOException {
    final EventExecutor eventExecutor = this.channelHandlerContext.executor();
    assert eventExecutor != null;
    if (this.inEventLoop()) {
      try {
        callable.call();
      } catch (final RuntimeException | IOException throwMe) {
        throw throwMe;
      } catch (final Exception everythingElse) {
        throw new IOException(everythingElse.getMessage(), everythingElse);
      }
    } else {
      eventExecutor.submit(callable);
    }
  }

}
