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

import io.netty.util.ReferenceCounted;

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

  private volatile ScheduledFuture<?> suspendTimeoutFuture;

  private volatile Runnable suspendTimeoutHandler;

  private volatile boolean responseWritten;

  private volatile ChunkedInput<?> chunkedInput;

  private volatile ReferenceCounted byteBuf;

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
      //
      // TODO: suppose this flush goes out with a 200.  And then the
      // output stream writes a bunch of crap, or blows up.  We want
      // to "retract" this write but it's too late.  On the other hand
      // we don't want to buffer everything I don't think.
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
          // Positive content length.
          byteBuf = this.channelHandlerContext.alloc().ioBuffer((int)contentLength);
        } else {
          // Negative content length or ridiculously huge content
          // length so ignore it for capacity purposes.
          assert contentLength != 0L;
          byteBuf = this.channelHandlerContext.alloc().ioBuffer();
        }
        assert byteBuf != null;

        // We store a reference to it ONLY so that we can release it
        // at a later date.
        this.byteBuf = byteBuf;

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
          this.byteBuf = null;
          byteBuf.release();
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
        returnValue =
          new EventLoopPinnedByteBufOutputStream(this.channelHandlerContext.executor(),
                                                 byteBuf,
                                                 null);
      } else {
        returnValue = null;
      }
    }
    return returnValue;
  }

  /**
   * Creates and returns a new {@link ChunkedInput} instance whose
   * {@link ChunkedInput#readChunk(ByteBufAllocator)} method will
   * stream {@link ByteBuf} chunks to the Netty transport.
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
  protected ChunkedInput<ByteBuf> createChunkedInput(final EventExecutor eventExecutor, final ByteBuf source, final long contentLength) {
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
    final ReferenceCounted byteBuf = this.byteBuf;
    this.byteBuf = null;
    this.channelHandlerContext.executor().submit((Callable<Void>)() -> {
        assert inEventLoop();
        if (chunkedInput == null) {
          assert byteBuf == null;
        } else {
          assert byteBuf != null;
          assert byteBuf.refCnt() == 1;
          
          // Mark our ChunkedInput as *closed to new input*.  It may
          // still contain contents that need to be read.  (This
          // effectively flips an internal switch in the ChunkedInput
          // that allows for isEndOfInput() to one day return true.)
          chunkedInput.close();
          
          // This will tell the ChunkedWriteHandler outbound from us
          // to "drain" our ByteBufChunkedInput via successive calls
          // to #readChunk(ByteBufAllocator).  The isEndOfInput()
          // method will be called as part of this process and will
          // eventually return true.
          channelHandlerContext.flush();
          
          // Assert that the ChunkedInput is drained and release its
          // wrapped ByteBuf.
          assert chunkedInput.isEndOfInput();
          final boolean released = byteBuf.release();
          assert released;
          
          // Send the magic message that tells the HTTP machinery to
          // finish up.
          channelHandlerContext.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
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
   * @exception RuntimeException if an error occurs while processing
   * the supplied {@link Throwable}; the supplied {@link Throwable}
   * will be {@linkplain Throwable#addSuppressed(Throwable) added as a
   * suppressed <code>Throwable</code>}
   *
   * @exception Error if an error occurs while processing the supplied
   * {@link Throwable}; the supplied {@link Throwable} will be
   * {@linkplain Throwable#addSuppressed(Throwable) added as a
   * suppressed <code>Throwable</code>}
   *
   * @see ContainerResponseWriter#failure(Throwable)
   */
  @Override
  public void failure(final Throwable throwable) {
    assert !this.inEventLoop();

    try {
      final HttpMessage httpMessage = new DefaultFullHttpResponse(this.httpRequest.protocolVersion(),
                                                                  HttpResponseStatus.INTERNAL_SERVER_ERROR);
      HttpUtil.setContentLength(httpMessage, 0L);
      final ChunkedInput<?> chunkedInput = this.chunkedInput;
      this.chunkedInput = null;
      final ReferenceCounted byteBuf = this.byteBuf;
      this.byteBuf = null;
      this.channelHandlerContext.executor().submit(() -> {
          try {
            assert inEventLoop();
            channelHandlerContext.write(httpMessage);
            if (chunkedInput == null) {
              assert byteBuf == null;
            } else {
              assert byteBuf != null;
              assert byteBuf.refCnt() == 1;
              final boolean released = byteBuf.release();
              assert released;
            }
          } catch (final RuntimeException | Error throwMe) {
            if (throwable != null) {
              throwMe.addSuppressed(throwable);
            }
            throw throwMe;
          } finally {
            channelHandlerContext.flush();
            channelHandlerContext.close();
          }
          return null;
        });
    } catch (final RuntimeException | Error throwMe) {
      if (throwable != null) {
        throwMe.addSuppressed(throwable);
      }
      throw throwMe;
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
