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

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler; // for javadoc only

import io.netty.util.ReferenceCounted;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

/**
 * A partial {@link ContainerResponseWriter} implementation that is
 * aware of Netty constructs.
 *
 * @param <T> a type representing the "headers and status" portion of
 * an incoming HTTP or HTTP/2 request
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #writeResponseStatusAndHeaders(long, ContainerResponse)
 *
 * @see #commit()
 *
 * @see #failure(Throwable)
 */
public abstract class AbstractNettyContainerResponseWriter<T> implements ContainerResponseWriter {


  /*
   * Static fields.
   */

  private static final String cn = AbstractNettyContainerResponseWriter.class.getName();

  private static final Logger logger = Logger.getLogger(cn);

  private static final GenericFutureListener<? extends Future<? super Void>> listener = f -> {
    final Throwable cause = f.cause();
    if (cause != null && logger.isLoggable(Level.SEVERE)) {
      logger.log(Level.SEVERE, cause.getMessage(), cause);
    }    
  };


  /*
   * Instance fields.
   */


  /**
   * The incoming HTTP request.
   *
   * <p>This field may be {@code null}.</p>
   */
  protected final T requestObject;

  /**
   * The {@link ChannelHandlerContext} representing the current Netty execution.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see ChannelHandlerContext
   */
  protected final ChannelHandlerContext channelHandlerContext;

  private final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier;

  private volatile ScheduledFuture<?> suspendTimeoutFuture;

  private volatile Runnable suspendTimeoutHandler;

  private volatile BoundedChunkedInput<?> chunkedInput;

  private volatile ReferenceCounted byteBuf;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link AbstractNettyContainerResponseWriter} implementation.
   *
   * @param requestObject an object representing the "headers and
   * status" portion of an incoming HTTP or HTTP/2 request; may be
   * {@code null} somewhat pathologically
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param scheduledExecutorServiceSupplier a {@link Supplier} of
   * {@link ScheduledExecutorService} instances that can be used to
   * offload tasks from the Netty event loop; must not be {@code null}
   *
   * @exception NullPointerException if either {@code
   * channelHandlerContext} or {@link
   * scheduledExecutorServiceSupplier} is {@code null}
   */
  protected AbstractNettyContainerResponseWriter(final T requestObject,
                                                 final ChannelHandlerContext channelHandlerContext,
                                                 final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    super();
    this.requestObject = requestObject;
    this.channelHandlerContext = Objects.requireNonNull(channelHandlerContext);
    this.scheduledExecutorServiceSupplier = Objects.requireNonNull(scheduledExecutorServiceSupplier);
  }


  /*
   * Instance methods.
   */


  /*
   * ContainerResponseWriter overrides.  Used only by Jersey, and on a
   * non-Netty-EventLoop-affiliated thread.
   */

  /**
   * Implements the {@link
   * ContainerResponseWriter#writeResponseStatusAndHeaders(long,
   * ContainerResponse)} method by calling the {@link
   * #writeAndFlushStatusAndHeaders(ContainerResponse, long,
   * ChannelPromise)} method, then {@linkplain
   * #needsOutputStream(long) determining whether an
   * <code>OutputStream</code> is needed}, and allocating and
   * returning a {@link EventLoopPinnedByteBufOutputStream} that works
   * properly with the Netty machinery if so.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @param contentLength the length in bytes that will be written to
   * the {@link OutputStream} that will be returned; may be {@code
   * -1L} to indicate an unknown content length but otherwise must be
   * zero or a positive {@code long}
   *
   * @param containerResponse the {@link ContainerResponse} for which
   * a write is to take place; must not be {@code null}
   *
   * @return an {@link OutputStream}, or {@code null}
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null}
   *
   * @exception IllegalStateException if a subclass has overridden
   * {@link #createChunkedInput(EventExecutor, ByteBuf, long)} to
   * return {@code null}
   *
   * @exception ContainerException if an error occurs
   *
   * @see #writeAndFlushStatusAndHeaders(ContainerResponse, long,
   * ChannelPromise)
   *
   * @see #needsOutputStream(long)
   *
   * @see #createChunkedInput(EventExecutor, ByteBuf, long)
   *
   * @see EventLoopPinnedByteBufOutputStream
   */
  @Override
  public final OutputStream writeResponseStatusAndHeaders(final long contentLength, final ContainerResponse containerResponse) {
    final String mn = "writeResponseStatusAndHeaders";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn, new Object[] { Long.valueOf(contentLength), containerResponse });
    }

    Objects.requireNonNull(containerResponse);
    assert !this.inEventLoop();

    final ChannelPromise statusAndHeadersPromise = this.channelHandlerContext.newPromise();
    assert statusAndHeadersPromise != null;
    statusAndHeadersPromise.addListener(listener);

    this.writeAndFlushStatusAndHeaders(containerResponse, contentLength, statusAndHeadersPromise);

    final OutputStream returnValue;
    if (this.needsOutputStream(contentLength)) {
      assert contentLength != 0L;

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
        byteBuf = this.channelHandlerContext.alloc().ioBuffer();
      }
      assert byteBuf != null;

      // A ChunkedInput despite its name has nothing to do with
      // chunked Transfer-Encoding.  It is usable anywhere you like.
      // So here, because writes are happening on an OutputStream in
      // Jersey-land on a separate thread, and are then being run on
      // the event loop, unless we were to block the event loop and
      // wait for the OutputStream to close, we wouldn't know when
      // to do the write.  So we use ChunkedInput even in cases
      // where the Content-Length is set to a positive integer.
      final BoundedChunkedInput<?> chunkedInput = this.createChunkedInput(this.channelHandlerContext.executor(), byteBuf, contentLength);
      if (chunkedInput == null) {
        // A user's implementation of createChunkedInput() behaved
        // badly.  Clean up and bail out.
        byteBuf.release();
        throw new IllegalStateException("createChunkedInput() == null");
      }
      this.chunkedInput = chunkedInput;

      // We store a reference to the allocated ByteBuf ONLY so that we
      // can release it at #failure(Throwable) time or if
      // createChunkedInput() returns null incorrectly.  Otherwise the
      // BoundedChunkedInput's close() method is responsible for
      // releasing it properly.
      this.byteBuf = byteBuf;

      // Enqueue a task that will query the ChunkedInput for
      // its chunks via its readChunk() method on the event loop.
      final ChannelPromise chunkedInputWritePromise = this.channelHandlerContext.newPromise();
      assert chunkedInputWritePromise != null;
      chunkedInputWritePromise.addListener(listener);
      channelHandlerContext.write(chunkedInput, chunkedInputWritePromise);

      // Then return an OutputStream implementation that writes to the
      // very same ByteBuf.  The net effect is that as this stream
      // writes to the ByteBuf, the ChunkedWriteHandler consuming the
      // ChunkedInput by way of its readChunk() method, on the event
      // loop, will stream the results as they are made available.  We
      // can get away with this
      // two-threads-interacting-with-the-same-ByteBuf-without-synchronization
      // use case because one thread is affecting only the ByteBuf's
      // reader index, and the other is affecting only the ByteBuf's
      // writer index.  See
      // https://stackoverflow.com/questions/58403725/although-bytebuf-is-not-guaranteed-to-be-thread-safe-will-this-use-case-be-ok
      // for more.
      returnValue = new ByteBufOutputStream(byteBuf);
    } else {
      returnValue = null;
    }

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn, returnValue);
    }
    return returnValue;
  }

  /**
   * Invoked on a non-event-loop thread by Jersey when, as far as
   * Jersey is concerned, all processing has completed successfully.
   *
   * <p>This implementation ensures that {@link
   * BoundedChunkedInput#setEndOfInput()} is called on the Netty event
   * loop on the {@link BoundedChunkedInput} returned by the {@link
   * #createChunkedInput(EventExecutor, ByteBuf, long)} method, and
   * that a {@linkplain #writeLastContentMessage(ChannelPromise) final
   * content message is written immediately afterwards}, and that the
   * {@link ChannelHandlerContext} is {@linkplain
   * ChannelHandlerContext#flush() flushed}.  This will result in a
   * downstream {@link ChunkedWriteHandler} writing the underlying
   * data and calling {@link ChunkedInput#close()} at the proper time.
   * All of these invocations occur on the event loop.</p>
   *
   * @see ChunkedInput#close()
   *
   * @see #createChunkedInput(EventExecutor, ByteBuf, long)
   *
   * @see #writeLastContentMessage(ChannelPromise)
   *
   * @see ChannelHandlerContext#write(Object)
   *
   * @see ChannelHandlerContext#flush()
   *
   * @see ContainerResponseWriter#commit()
   *
   * @see ChunkedWriteHandler
   */
  @Override
  public final void commit() {
    final String mn = "commit";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn);
    }

    assert !this.inEventLoop();
    final BoundedChunkedInput<?> chunkedInput = this.chunkedInput;
    this.chunkedInput = null;
    if (chunkedInput != null) {
      final ChannelPromise promise = this.channelHandlerContext.newPromise();
      assert promise != null;
      promise.addListener(listener);
      this.channelHandlerContext.executor().submit((Callable<Void>)() -> {
          assert inEventLoop();
          chunkedInput.setEndOfInput();
          this.writeLastContentMessage(promise);
          channelHandlerContext.flush();
          return null;
        }).addListener(listener);
    }

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn);
    }
  }

  /**
   * Invoked on a non-event-loop thread by Jersey when, as far as
   * Jersey is concerned, all processing has completed unsuccessfully.
   *
   * <p><strong>This method never returns.  It always throws a {@link
   * RuntimeException} or an {@link Error} of some
   * variety.</strong></p>
   *
   * <h2>Design Notes</h2>
   *
   * <p><a
   * href="https://github.com/jax-rs/spec/blob/b4b0d91fc902d58dfa1670b2ee1bc34a640bb6f5/chapters/resources.tex#L143-L144"
   * target="_parent">JAX-RS: Java&trade; API for RESTful Web
   * Services, section 3.3.4</a> governs this method, but it is
   * somewhat ambiguous whether this method represents the container
   * itself.  All Jersey-supplied {@link ContainerResponseWriter}
   * implementations (except their <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/eafb9bdcb82dfa3fd76dd957d307b99d4a22c87f/containers/netty-http/src/main/java/org/glassfish/jersey/netty/httpserver/NettyResponseWriter.java#L177-L181"
   * target="_parent">Netty container</a>, which seems to be <a
   * href="https://github.com/jersey/jersey/pull/3791"
   * target="_parent">buggy in this regard</a>) rethrow the supplied
   * {@link Throwable} as some kind of {@link RuntimeException} from
   * their implementations of this method, so we follow suit.</p>
   *
   * @param throwable the {@link Throwable} that caused failure;
   * strictly speaking may be {@code null}
   *
   * @exception ContainerException <strong>thrown whenever this method
   * completes without further problems, or if the supplied {@link
   * Throwable} is itself a {@link ContainerException}, in order to
   * comply with section 3.3.4 of the JAX-RS 2.1
   * specification</strong>
   *
   * @exception RuntimeException if the supplied {@link Throwable} is
   * itself a {@link RuntimeException} and this method completes
   * without further problems, or if an error occurs while processing
   * the supplied {@link Throwable} in which case the supplied {@link
   * Throwable} will be {@linkplain Throwable#addSuppressed(Throwable)
   * added as a suppressed <code>Throwable</code>}
   *
   * @exception Error if the supplied {@link Throwable} is itself an
   * {@link Error} and this method completes without further problems,
   * or if an error occurs while processing the supplied {@link
   * Throwable} in which case the supplied {@link Throwable} will be
   * {@linkplain Throwable#addSuppressed(Throwable) added as a
   * suppressed <code>Throwable</code>}
   *
   * @see ContainerResponseWriter#failure(Throwable)
   *
   * @see <a
   * href="https://github.com/jax-rs/spec/blob/b4b0d91fc902d58dfa1670b2ee1bc34a640bb6f5/chapters/resources.tex#L143-L144"
   * target="_parent">JAX-RS: Java&trade; API for RESTful Web
   * Services, section 3.3.4</a>
   */
  @Override
  public final void failure(final Throwable throwable) {
    final String mn = "failure";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn, throwable);
    }

    assert !this.inEventLoop();

    this.chunkedInput = null;
    final ReferenceCounted byteBuf = this.byteBuf;
    this.byteBuf = null;
    try {
      final ChannelPromise promise = this.channelHandlerContext.newPromise();
      assert promise != null;
      promise.addListener(listener);
      this.channelHandlerContext.executor().submit((Callable<Void>)() -> {
          try {
            assert inEventLoop();
            this.writeFailureMessage(promise);
          } catch (final RuntimeException | Error throwMe) {
            if (throwable != null) {
              throwMe.addSuppressed(throwable);
            }
            throw throwMe;
          } finally {
            try {
              try {
                channelHandlerContext.flush();
              } catch (final RuntimeException | Error throwMe2) {
                if (throwable != null) {
                  throwMe2.addSuppressed(throwable);
                }
                throw throwMe2;
              }
              try {
                channelHandlerContext.close();
              } catch (final RuntimeException | Error throwMe2) {
                if (throwable != null) {
                  throwMe2.addSuppressed(throwable);
                }
                throw throwMe2;
              }
            } finally {
              assert byteBuf != null;
              assert byteBuf.refCnt() == 1;
              final boolean released = byteBuf.release();
              assert released;
            }
          }
          return null;
        }).addListener(listener);
    } catch (final RuntimeException | Error throwMe) {
      // There was a problem submitting the task to the Netty
      // infrastructure.  Make sure we don't lose the original
      // Throwable.
      if (throwable != null) {
        throwMe.addSuppressed(throwable);
      }
      throw throwMe;
    }

    // See
    // https://github.com/eclipse-ee4j/jersey/blob/b7fbb3e75b16feb4d61cd6a5526a66962bf3ae83/containers/grizzly2-http/src/main/java/org/glassfish/jersey/grizzly2/httpserver/GrizzlyHttpContainer.java#L256-L281,
    // https://github.com/eclipse-ee4j/jersey/blob/b7fbb3e75b16feb4d61cd6a5526a66962bf3ae83/containers/jdk-http/src/main/java/org/glassfish/jersey/jdkhttp/JdkHttpHandlerContainer.java#L301-L311,
    // https://github.com/eclipse-ee4j/jersey/blob/b7fbb3e75b16feb4d61cd6a5526a66962bf3ae83/containers/jetty-http/src/main/java/org/glassfish/jersey/jetty/JettyHttpContainer.java#L332-L357,
    // https://github.com/eclipse-ee4j/jersey/blob/eafb9bdcb82dfa3fd76dd957d307b99d4a22c87f/containers/jersey-servlet-core/src/main/java/org/glassfish/jersey/servlet/internal/ResponseWriter.java#L212-L238,
    // and
    // https://github.com/eclipse-ee4j/jersey/blob/eafb9bdcb82dfa3fd76dd957d307b99d4a22c87f/containers/simple-http/src/main/java/org/glassfish/jersey/simple/SimpleContainer.java#L219-L232.
    //
    // All these Jersey-supplied containers rethrow the supplied
    // Throwable. But the Netty one does not:
    // https://github.com/eclipse-ee4j/jersey/blob/eafb9bdcb82dfa3fd76dd957d307b99d4a22c87f/containers/netty-http/src/main/java/org/glassfish/jersey/netty/httpserver/NettyResponseWriter.java#L177-L181.
    //
    // See also https://github.com/jersey/jersey/pull/3791, where it
    // is implied that the fact that Jersey's own Netty integration
    // does NOT rethrow it is a bug.  There is some ambiguity about
    // whether the failure(Throwable) method (this method) is supposed
    // to fully handle the supplied Throwable or if it is supposed to
    // propagate it outwards.  The ambiguity arises because you could
    // see that this very method is the place where an unhandled
    // Throwable is propagated--after all, we're in a
    // ContainerResponseWriter implementation--or you could see that
    // whatever is housing this class is the container, so the
    // supplied Throwable should be wrapped and rethrown.  JAX-RS
    // section 3.3.4 is not very helpful here.  We follow the other
    // Jersey-supplied containers here.
    if (throwable instanceof RuntimeException) {
      throw (RuntimeException)throwable;
    } else if (throwable instanceof Exception) {
      throw new ContainerException(throwable.getMessage(), throwable);
    } else if (throwable instanceof Error) {
      throw (Error)throwable;
    } else {
      throw new InternalError(throwable.getMessage(), throwable);
    }
  }

  /**
   * Returns {@code true} when invoked.
   *
   * <p>Note that this is a default value.  Response buffering <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778"
   * target="_parent">can be configured</a>.</p>
   *
   * @return {@code true} when invoked
   *
   * @see ContainerResponseWriter#enableResponseBuffering()
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-server/src/main/java/org/glassfish/jersey/server/ContainerResponse.java#L352-L363"
   * target="_parent"><code>ContainerResponse.java</code></a>
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778"
   * target="_parent"><code>OutboundMessageContext.java</code></a>
   */
  @Override
  public boolean enableResponseBuffering() {
    assert !this.inEventLoop();

    return true;
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


  /*
   * Abstract methods.
   */


  /**
   * Returns {@code true} if the current {@link ContainerResponse}
   * being written needs an {@link OutputStream}.
   *
   * <p>This method is called by the {@link
   * #writeResponseStatusAndHeaders(long, ContainerResponse)}
   * method.</p>
   *
   * @param contentLength the length of the content in bytes; may be
   * less than {@code 0} if the content length is unknown
   *
   * @return {@code true} if an {@link OutputStream} should be created
   * and set up; {@code false} otherwise
   *
   * @see #writeResponseStatusAndHeaders(long, ContainerResponse)
   */
  protected abstract boolean needsOutputStream(final long contentLength);

  /**
   * Called to allow this {@link AbstractNettyContainerResponseWriter}
   * implementation to {@linkplain ChannelHandlerContext#write(Object)
   * write} a message that indicates that there is not going to be any
   * further content sent back to the client.
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelPromise} is
   * {@code null}
   *
   * @see #channelHandlerContext
   *
   * @see ChannelHandlerContext#write(Object)
   */
  protected abstract void writeLastContentMessage(final ChannelPromise channelPromise);

  /**
   * Called by the {@link #writeResponseStatusAndHeaders(long,
   * ContainerResponse)} method when this {@link
   * AbstractNettyContainerResponseWriter} should {@linkplain
   * ChannelHandlerContext#writeAndFlush(Object, ChannelPromise) write
   * and flush} a message containing relevant headers and the HTTP
   * status of the response being processed.
   *
   * @param containerResponse the {@link ContainerResponse} being
   * processed; must not be {@code null}
   *
   * @param contentLength the length of the content in bytes; may be
   * less than {@code 0} if the content length is unknown
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code containerResponse} or
   * {@code channelPromise} is {@code null}
   *
   * @see #writeResponseStatusAndHeaders(long, ContainerResponse)
   */
  protected abstract void writeAndFlushStatusAndHeaders(final ContainerResponse containerResponse,
                                                        final long contentLength,
                                                        final ChannelPromise channelPromise);

  /**
   * Called to create a {@link BoundedChunkedInput} that will be used
   * as part of setting up an {@link OutputStream} for the {@link
   * ContainerResponse} being processed.
   *
   * <p>Implementations of this method must not return {@code
   * null}.</p>
   *
   * <p>Implementations of this method will be called only after
   * {@link #needsOutputStream(long)} has returned {@code true}.</p>
   *
   * <p>Implementations of this method will always be called on a
   * {@link Thread} that is <strong>not</strong> the {@linkplain
   * #inEventLoop() Netty event loop}.</p>
   *
   * <p>Implementations of this method must arrange to read from the
   * supplied {@link ByteBuf}.  Undefined behavior will result if this
   * is not the case.</p>
   *
   * <p>The {@link BoundedChunkedInput} that is returned must
   * {@linkplain ByteBuf#release() release} the supplied {@link
   * ByteBuf} during an invocation of the {@link ChunkedInput#close()}
   * method.</p>
   *
   * <p>Most implementations of this method should return some variety
   * of {@link FunctionalByteBufChunkedInput}.</p>
   *
   * @param eventExecutor an {@link EventExecutor} supplied for
   * convenience in case any operations need to be performed on the
   * supplied {@link ByteBuf} or on the Netty event loop in general;
   * must not be {@code null}
   *
   * @param source the {@link ByteBuf} serving as the source of data;
   * must not be {@code null}; must only be read from, not written to
   *
   * @param contentLength the overall length of the content, in bytes,
   * for which a {@link ChunkedInput} is being created; must not be
   * {@code 0}; may be less than {@code 0} if the content length is
   * unknown
   *
   * @return a non-{@code null} {@link ChunkedInput} implementation
   * that will treat the supplied {@code ByteBuf} as its source in
   * some manner
   *
   * @exception NullPointerException if {@code eventExecutor} or
   * {@code source} is {@code null}
   *
   * @see ChunkedInput
   */
  protected abstract BoundedChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor,
                                                               final ByteBuf source,
                                                               final long contentLength);

  /**
   * Called to {@linkplain ChannelHandlerContext#write(Object) write a
   * message} indicating a general unspecified server failure.
   *
   * <p>Implementations of this method are called from the {@link
   * #failure(Throwable)} method.</p>
   *
   * <p>Implementations of this method will always be called on a
   * {@link Thread} that is <strong>not</strong> the {@linkplain
   * #inEventLoop() Netty event loop}.</p>
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelPromise} is
   * {@code null}
   *
   * @see #failure(Throwable)
   */
  protected abstract void writeFailureMessage(final ChannelPromise channelPromise);


  /*
   * Utility methods.
   */


  /**
   * Returns {@code true} if the current thread is Netty's {@linkplain
   * EventExecutor#inEventLoop() event loop}.
   *
   * @return {@code true} if the current thread is Netty's {@linkplain
   * EventExecutor#inEventLoop() event loop}; {@code false} otherwise
   *
   * @see EventExecutor#inEventLoop()
   */
  protected final boolean inEventLoop() {
    return this.channelHandlerContext.executor().inEventLoop();
  }

  /**
   * A utility function that copies entries from a source {@link Map}
   * by passing each entry to the supplied {@link BiConsumer},
   * transforming the keys beforehand using the supplied {@link
   * UnaryOperator} and that is intended in this framework to be used
   * to copy HTTP or HTTP/2 headers to and from the proper places.
   *
   * @param headersSource the source of the headers to copy; may be
   * {@code null} in which case no action will be taken
   *
   * @param keyTransformer a {@link UnaryOperator} that transforms a
   * header name; if {@code null} then the return value of {@link
   * UnaryOperator#identity()} will be used instead
   *
   * @param headersTarget where the headers will be copied to; may be
   * {@code null} in which case no action will be taken
   *
   * @see #writeAndFlushStatusAndHeaders(ContainerResponse, long,
   * ChannelPromise)
   */
  public static final void copyHeaders(final Map<? extends String, ? extends List<String>> headersSource,
                                       UnaryOperator<String> keyTransformer,
                                       final BiConsumer<? super String, ? super List<String>> headersTarget) {
    if (headersTarget != null && headersSource != null && !headersSource.isEmpty()) {
      final Collection<? extends Entry<? extends String, ? extends List<String>>> entrySet = headersSource.entrySet();
      if (entrySet != null && !entrySet.isEmpty()) {
        if (keyTransformer == null) {
          keyTransformer = UnaryOperator.identity();
        }
        for (final Entry<? extends String, ? extends List<String>> entry : entrySet) {
          if (entry != null) {
            headersTarget.accept(keyTransformer.apply(entry.getKey()), entry.getValue());
          }
        }
      }
    }
  }

}
