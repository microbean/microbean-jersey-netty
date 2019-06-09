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

import java.io.OutputStream;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.handler.stream.ChunkedInput;

import org.glassfish.jersey.internal.inject.InjectionManager;

import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

import org.glassfish.jersey.spi.ScheduledExecutorServiceProvider;

public class NettyContainerResponseWriter implements ContainerResponseWriter {

  private static final ChannelFutureListener FLUSH_LISTENER = cf -> cf.channel().flush();

  private final ChannelHandlerContext channelHandlerContext;

  private final HttpMessage httpRequest;

  private final Supplier<? extends HttpMethod> methodSupplier;

  private final InjectionManager injectionManager;

  private volatile long contentLength;

  private volatile ScheduledFuture<?> suspendTimeoutFuture;

  private volatile Runnable suspendTimeoutHandler;

  private volatile boolean responseWritten;

  private volatile ChunkedInput<?> chunkedInput;
  
  public NettyContainerResponseWriter(final HttpRequest httpRequest,
                                      final ChannelHandlerContext channelHandlerContext,
                                      final InjectionManager injectionManager) {
    this(httpRequest, httpRequest::method, channelHandlerContext, injectionManager);
  }

  private NettyContainerResponseWriter(final HttpMessage httpRequest,
                                       final Supplier<? extends HttpMethod> methodSupplier,
                                       final ChannelHandlerContext channelHandlerContext,
                                       final InjectionManager injectionManager) {
    super();
    this.httpRequest = Objects.requireNonNull(httpRequest);
    this.methodSupplier = Objects.requireNonNull(methodSupplier);
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

    this.contentLength = contentLength;

    final OutputStream returnValue;

    // Jersey's native Netty support does this.  I don't know why.  We follow suit.
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
      
      if (contentLength < 0) {
        HttpUtil.setTransferEncodingChunked(httpResponse, true);
      } else {
        HttpUtil.setContentLength(httpResponse, contentLength);
      }

      final Map<? extends String, ? extends List<String>> containerResponseHeaders = containerResponse.getStringHeaders();
      if (containerResponseHeaders != null && !containerResponseHeaders.isEmpty()) {
        final Collection<? extends Entry<? extends String, ? extends List<String>>> entrySet = containerResponseHeaders.entrySet();
        if (entrySet != null && !entrySet.isEmpty()) {
          final HttpHeaders headers = httpResponse.headers();
          for (final Entry<? extends String, ? extends List<String>> entry : entrySet) {
            if (entry != null) {
              headers.add(entry.getKey(), entry.getValue());
            }
          }
        }
      }

      if (HttpUtil.isKeepAlive(this.httpRequest)) {
        HttpUtil.setKeepAlive(httpResponse, true);
      }

      // Enqueue a task to write the headers on the event loop.
      channelHandlerContext.writeAndFlush(httpResponse);

      if (needsOutputStream(contentLength)) {

        // We've determined that there is a payload/entity.  Our
        // ultimate responsibility is to return an OutputStream the
        // end user's Jersey resource class will write to.
        //
        // Allocate a ByteBuf suitable for doing IO.  This could be
        // heap-based or native.  We don't care; we trust Netty.
        final ByteBuf byteBuf = this.channelHandlerContext.alloc().ioBuffer();
        assert byteBuf != null;

        // Ensure that this buffer is released when/if the channel is
        // closed.
        channelHandlerContext.channel().closeFuture().addListener(ignored -> {
            byteBuf.release();
          });

        // TODO: normally you'd think you'd only write a ChunkedInput
        // implementation if you were sure that the Transfer-Encoding
        // was Chunked.  But because writes are happening on an
        // OutputStream in Jersey-land on a separate thread, and are
        // then being run on the event loop, unless we were to block
        // the event loop and wait for the OutputStream to close, we
        // wouldn't know when to do the write.  So we use ChunkedInput
        // even in cases where the Content-Length is set to a positive
        // integer.  We may need to revisit this.

        this.chunkedInput = new ByteBufChunkedInput(byteBuf);

        // Enqueue a task that will query the ByteBufChunkedInput for
        // its chunks via its readChunk() method on the event loop.
        channelHandlerContext.write(this.chunkedInput);

        returnValue = new EventLoopPinnedByteBufOutputStream(this.channelHandlerContext.executor(), byteBuf);
      } else {
        // channelHandlerContext.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        returnValue = null;
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
        // TODO: not sure about this
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
    final ChunkedInput<?> chunkedInput = this.chunkedInput;
    this.chunkedInput = null;
    this.channelHandlerContext.executor().submit((Callable<Void>)() -> {
        try {
          if (chunkedInput != null) {
            chunkedInput.close();
            channelHandlerContext.write(LastHttpContent.EMPTY_LAST_CONTENT);
          }
        } finally {
          this.channelHandlerContext.flush();
        }
        return null;
      });
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
   * Utility methods.
   */


  private final boolean needsOutputStream(final long contentLength) {
    return contentLength != 0 && !this.isHEADRequest();
  }

  private final boolean isHEADRequest() {
    return HttpMethod.HEAD.equals(this.methodSupplier.get());
  }

  private final boolean inEventLoop() {
    return this.channelHandlerContext.executor().inEventLoop();
  }

}
