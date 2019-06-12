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
import java.util.concurrent.ScheduledExecutorService;
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

import io.netty.util.concurrent.EventExecutor;

import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

import org.glassfish.jersey.spi.ScheduledExecutorServiceProvider;

/**
 * A {@link ContainerResponseWriter} that takes care not to block the
 * Netty event loop.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see ContainerResponseWriter
 *
 * @see #writeResponseStatusAndHeaders(long, ContainerResponse)
 *
 * @see #createChunkedInput(EventExecutor, ByteBuf, long)
 */
public class NettyContainerResponseWriter implements ContainerResponseWriter {


  /*
   * Instance fields.
   */

  
  private final ChannelHandlerContext channelHandlerContext;

  private final HttpMessage httpRequest;

  private final Supplier<? extends HttpMethod> methodSupplier;

  private final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier;

  private volatile long contentLength;

  private volatile ScheduledFuture<?> suspendTimeoutFuture;

  private volatile Runnable suspendTimeoutHandler;

  private volatile boolean responseWritten;

  private volatile ChunkedInput<?> chunkedInput;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link NettyContainerResponseWriter}.
   *
   * @param httpRequest the {@link HttpRequest} being responded to;
   * must not be {@code null}
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param scheduledExecutorServiceSupplier a {@link Supplier} that
   * can {@linkplain Supplier#get() supply} a {@link
   * ScheduledExecutorService}; must not be {@code null}
   *
   * @exception NullPointerException if any of the parameters is
   * {@code null}
   */
  public NettyContainerResponseWriter(final HttpRequest httpRequest,
                                      final ChannelHandlerContext channelHandlerContext,
                                      final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    this(httpRequest, httpRequest::method, channelHandlerContext, scheduledExecutorServiceSupplier);
  }

  private NettyContainerResponseWriter(final HttpMessage httpRequest,
                                       final Supplier<? extends HttpMethod> methodSupplier,
                                       final ChannelHandlerContext channelHandlerContext,
                                       final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    super();
    this.httpRequest = Objects.requireNonNull(httpRequest);
    this.methodSupplier = Objects.requireNonNull(methodSupplier);
    this.channelHandlerContext = Objects.requireNonNull(channelHandlerContext);
    this.scheduledExecutorServiceSupplier = Objects.requireNonNull(scheduledExecutorServiceSupplier);
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

    // Jersey's native Netty support does this.  I don't know why.  We
    // follow suit; there's probably an edge case that gave rise to
    // this.
    final boolean responseWritten = this.responseWritten;
    if (responseWritten) {
      returnValue = null;
    } else {
      this.responseWritten = true;

      final String reasonPhrase = containerResponse.getStatusInfo().getReasonPhrase();
      final int statusCode = containerResponse.getStatus();
      final HttpResponseStatus status = reasonPhrase == null ? HttpResponseStatus.valueOf(statusCode) : new HttpResponseStatus(statusCode, reasonPhrase);

      final HttpResponse httpResponse;
      if (contentLength == 0L) {
        httpResponse = new DefaultFullHttpResponse(this.httpRequest.protocolVersion(), status);
      } else {
        httpResponse = new DefaultHttpResponse(this.httpRequest.protocolVersion(), status);
      }
      
      if (contentLength < 0L) {
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

      // Enqueue a task to write and flush the headers on the event
      // loop.
      channelHandlerContext.writeAndFlush(httpResponse);

      if (this.needsOutputStream(contentLength)) {

        // We've determined that there is a payload/entity.  Our
        // ultimate responsibility is to return an OutputStream the
        // end user's Jersey resource class will write to.
        //
        // Allocate a ByteBuf suitable for doing IO.  This could be
        // heap-based or native.  We don't care; we trust Netty.
        final ByteBuf byteBuf;
        if (contentLength > 0L && contentLength <= Integer.MAX_VALUE) {
          byteBuf = this.channelHandlerContext.alloc().ioBuffer((int)contentLength);
        } else {
          assert contentLength < 0L;
          byteBuf = this.channelHandlerContext.alloc().ioBuffer();
        }
        assert byteBuf != null;

        // Ensure that this buffer is released when/if the channel is
        // closed.
        channelHandlerContext.channel().closeFuture().addListener(ignored -> byteBuf.release());

        // A ChunkedInput despite its name has nothing to do with
        // chunked Transfer-Encoding.  It is usable anywhere you like.
        // So here, because writes are happening on an OutputStream in
        // Jersey-land on a separate thread, and are then being run on
        // the event loop, unless we were to block the event loop and
        // wait for the OutputStream to close, we wouldn't know when
        // to do the write.  So we use ChunkedInput even in cases
        // where the Content-Length is set to a positive integer.
        final ChunkedInput<?> chunkedInput = this.createChunkedInput(this.channelHandlerContext.executor(), byteBuf, contentLength);
        if (chunkedInput == null) {
          throw new IllegalStateException("createChunkedInput() == null");
        }
        this.chunkedInput = chunkedInput;

        // Enqueue a task that will query the ByteBufChunkedInput for
        // its chunks via its readChunk() method on the event loop.
        channelHandlerContext.write(this.chunkedInput);

        // Then return an OutputStream implementation that writes to
        // the very same ByteBuf, ensuring that writes take place on
        // the event loop.  The net effect is that as this stream
        // writes to the ByteBuf, the ChunkedWriteHandler consuming
        // the ChunkedInput by way of its readChunk() method, also on
        // the event loop, will stream the results as they are made
        // available.
        //
        // TODO: replace the null third parameter with a
        // GenericFutureListener that will Do The Right Thing™ with
        // any exceptions.  **Remember that this listener will be
        // invoked on the event loop.**
        returnValue = new EventLoopPinnedByteBufOutputStream(this.channelHandlerContext.executor(), byteBuf, null);
      } else {
        returnValue = null;
      }
    }
    return returnValue;
  }

  /**
   * Creates a new {@link ChunkedInput} instance whose {@link
   * ChunkedInput#readChunk(ByteBufAllocator)} method will stream
   * {@link ByteBuf} chunks to the Netty transport.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>In normal usage this method will be invoked on a thread that
   * is <strong>not</strong> {@linkplain EventExecutor#inEventLoop()
   * in the Netty event loop}.  Care <strong>must</strong> be taken if
   * an override of this method decides to invoke any methods on the
   * supplied {@link ByteBuf} to ensure those methods are invoked in
   * the Netty event loop.  This implementation does not invoke any
   * {@link ByteBuf} methods and overrides are strongly urged to
   * follow suit.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>This method is called from the {@link
   * #writeResponseStatusAndHeaders(long, ContainerResponse)} method.
   * Overrides must not call that method or an infinite loop may
   * result.</p>
   *
   * @param eventExecutor an {@link EventExecutor}, supplied for
   * convenience, that can be used to ensure certain tasks are
   * executed in the Netty event loop; must not be {@code null}
   *
   * @param source the {@link ByteBuf}, <strong>which might be
   * mutating</strong> in the Netty event loop, that the returned
   * {@link ChunkedInput} should read from in some way when its {@link
   * ChunkedInput#readChunk(ByteBufAllocator)} method is called from
   * the Netty event loop; must not be {@code null}
   *
   * @param contentLength a {@code long} representing the value of any
   * {@code Content-Length} header that might have been present; it is
   * guaranteed that when this method is invoked by the default
   * implementation of the {@link #writeResponseStatusAndHeaders(long,
   * ContainerResponse)} method this parameter will never be {@code
   * 0L} but might very well be less than {@code 0L} to indicate an
   * unknown content length
   *
   * @return a new {@link ChunkedInput} that reads in some way from
   * the supplied {@code source} when its {@link
   * ChunkedInput#readChunk(ByteBufAllocator)} method is called from
   * the Netty event loop; never {@code null}
   *
   * @see ByteBufChunkedInput#ByteBufChunkedInput(ByteBuf, long)
   *
   * @see ChunkedInput#readChunk(ByteBufAllocator)
   */
  protected ChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor, final ByteBuf source, final long contentLength) {
    return new ByteBufChunkedInput(source, contentLength);
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
          this.scheduledExecutorServiceSupplier.get().schedule(this.suspendTimeoutHandler, timeout, timeUnit);
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
        this.scheduledExecutorServiceSupplier.get().schedule(this.suspendTimeoutHandler, timeout, timeUnit);
    }
  }

  /**
   * Invoked on a non-event-loop thread by Jersey when, as far as
   * Jersey is concerned, all processing has completed successfully.
   *
   * <p>This implementation ensures that {@link ChunkedInput#close()}
   * is called on the Netty event loop on the {@link ChunkedInput}
   * returned by the {@link #createChunkedInput(EventExecutor,
   * ByteBuf, long)} method, and that a {@link
   * LastHttpContent#EMPTY_LAST_CONTENT} message is written
   * immediately afterwards, and that the {@link
   * ChannelHandlerContext} is {@linkplain
   * ChannelHandlerContext#flush() flushed}.  All of these invocations
   * occur on the event loop.</p>
   *
   * @see ChunkedInput#close()
   *
   * @see #createChunkedInput(EventExecutor, ByteBuf, long)
   *
   * @see LastHttpContent#EMPTY_LAST_CONTENT
   *
   * @see ChannelHandlerContext#write(Object)
   *
   * @see ChannelHandlerContext#flush()
   *
   * @see ContainerResponseWriter#commit()
   */
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

  /**
   * Invoked on a non-event-loop thread by Jersey when, as far as
   * Jersey is concerned, all processing has completed unsuccessfully.
   *
   * @param throwable the {@link Throwable} that caused failure;
   * strictly speaking may be {@code null}
   *
   * @see ContainerResponseWriter#failure(Throwable)
   */
  @Override
  public void failure(final Throwable throwable) {
    assert !this.inEventLoop();

    // Note that despite the Exception-less signature of this method
    // JAX-RS specification section 3.3.4 says, in items number 3 and
    // 4: "Unchecked exceptions and errors that have not been mapped
    // MUST be re-thrown and allowed to propagate to the underlying
    // container", and "Checked exceptions and throwables that have
    // not been mapped and cannot be thrown directly MUST be wrapped
    // in a container-specific exception that is then thrown and
    // allowed to propagate to the underlying container."  It's a tiny
    // bit unclear as to where the container boundary is in this
    // method, but we'll throw a ContainerException if there is a
    // problem that arises with processing the supplied Throwable.
    try {
      this.channelHandlerContext.writeAndFlush(new DefaultFullHttpResponse(this.httpRequest.protocolVersion(),
                                                                           HttpResponseStatus.INTERNAL_SERVER_ERROR)).addListener(ChannelFutureListener.CLOSE);
    } catch (final RuntimeException runtimeException) {
      if (throwable == null) {
        throw runtimeException;
      } else {
        throwable.addSuppressed(runtimeException);
      }
    }
    if (throwable instanceof RuntimeException) {
      throw (RuntimeException)throwable;
    } else if (throwable != null) {
      throw new ContainerException(throwable.getMessage(), throwable);
    }
  }

  /**
   * Returns {@code true} when invoked.
   *
   * <p>Note that this is a default value.  Response buffering <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778">can
   * be configured</a>.</p>
   *
   * @return {@code true} when invoked
   *
   * @see ContainerResponseWriter#enableResponseBuffering()
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-server/src/main/java/org/glassfish/jersey/server/ContainerResponse.java#L352-L363"
   * target="_parent"><code> ContainerResponse.java</code></a>
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778"
   * target="_parent"><code> OutboundMessageContext.java</code></a>
   */
  @Override
  public boolean enableResponseBuffering() {
    assert !this.inEventLoop();

    return true;
  }


  /*
   * Utility methods.
   */


  private final boolean needsOutputStream(final long contentLength) {
    return contentLength != 0 && !HttpMethod.HEAD.equals(this.methodSupplier.get());
  }

  private final boolean inEventLoop() {
    return this.channelHandlerContext.executor().inEventLoop();
  }

}
