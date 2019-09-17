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

import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.stream.ChunkedInput;

import io.netty.util.ReferenceCounted;

import io.netty.util.concurrent.EventExecutor;

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

  private volatile ChunkedInput<?> chunkedInput;

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
   * ContainerResponseWriter overrides.  Used only by Jersey, and on a
   * non-Netty-EventLoop-affiliated thread.
   */
  
  
  @Override
  public final OutputStream writeResponseStatusAndHeaders(final long contentLength, final ContainerResponse containerResponse) throws ContainerException {
    Objects.requireNonNull(containerResponse);
    assert !this.inEventLoop();

    this.writeAndFlushStatusAndHeaders(containerResponse, contentLength);
    
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
      
      // We store a reference to it ONLY so that we can release it
      // at #commit() or #failure(Throwable) time.
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
        // A user's implementation of createChunkedInput() behaved
        // badly.  Clean up and bail out.
        this.byteBuf = null;
        byteBuf.release();
        throw new IllegalStateException("createChunkedInput() == null");
      }
      this.chunkedInput = chunkedInput;
      
      // Enqueue a task that will query the ChunkedInput for
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
    return returnValue;
  }

  /**
   * Invoked on a non-event-loop thread by Jersey when, as far as
   * Jersey is concerned, all processing has completed successfully.
   *
   * <p>This implementation ensures that {@link ChunkedInput#close()}
   * is called on the Netty event loop on the {@link ChunkedInput}
   * returned by the {@link #createChunkedInput(EventExecutor,
   * ByteBuf, long)} method, and that a {@linkplain
   * #writeLastContentMessage() final content message is written
   * immediately afterwards}, and that the {@link
   * ChannelHandlerContext} is {@linkplain
   * ChannelHandlerContext#flush() flushed}.  All of these invocations
   * occur on the event loop.</p>
   *
   * @see ChunkedInput#close()
   *
   * @see #createChunkedInput(EventExecutor, ByteBuf, long)
   *
   * @see #writeLastContentMessage()
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
    if (chunkedInput != null) {
      this.channelHandlerContext.executor().submit((Callable<Void>)() -> {
          assert inEventLoop();
          assert chunkedInput != null;
          assert byteBuf != null;
          assert byteBuf.refCnt() == 1;
            
          // Mark our ChunkedInput as *closed to new input*.  It may
          // still contain contents that need to be read.  (This
          // effectively flips an internal switch in the ChunkedInput
          // that allows for isEndOfInput() to one day return true.)
          chunkedInput.close();
          
          // This will tell the ChunkedWriteHandler outbound from us
          // to "drain" our ChunkedInput via successive calls
          // to #readChunk(ByteBufAllocator).  The isEndOfInput()
          // method will be called as part of this process and will
          // eventually return true.
          channelHandlerContext.flush();
          
          // Assert that the ChunkedInput is drained and release its
          // wrapped ByteBuf.
          assert chunkedInput.isEndOfInput();
          final boolean released = byteBuf.release();
          assert released;
          
          // Send whatever magic message it is that tells the HTTP
          // machinery to finish up.
          this.writeLastContentMessage();        
          return null;
        });
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
    if (!this.inEventLoop()) {
      final ContainerException throwMe = new ContainerException("!this.inEventLoop()");
      if (throwable != null) {
        throwMe.addSuppressed(throwable);
      }
      throw throwMe;
    }

    final ChunkedInput<?> chunkedInput = this.chunkedInput;
    this.chunkedInput = null;
    final ReferenceCounted byteBuf = this.byteBuf;
    this.byteBuf = null;
    if (chunkedInput != null) {
      try {
        this.channelHandlerContext.executor().submit(() -> {
            try {
              assert inEventLoop();
              this.writeFailureMessage();
              assert byteBuf != null;
              assert byteBuf.refCnt() == 1;
              final boolean released = byteBuf.release();
              assert released;
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
        // There was a problem submitting the task to the Netty
        // infrastructure.  Make sure we don't lose the original
        // Throwable.
        if (throwable != null) {
          throwMe.addSuppressed(throwable);
        }
        throw throwMe;
      }
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
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778">can
   * be configured</a>.</p>
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
   * @see #channelHandlerContext
   *
   * @see ChannelHandlerContext#write(Object)
   */
  protected abstract void writeLastContentMessage();
  
  /**
   * Called when this {@link AbstractNettyContainerResponseWriter}
   * should {@linkplain ChannelHandlerContext#writeAndFlush(Object)
   * write and flush} a message containing relevant headers and the
   * HTTP status of the response being processed.
   *
   * @param containerResponse the {@link ContainerResponse} being
   * processed; must not be {@code null}
   *
   * @param contentLength the length of the content in bytes; may be
   * less than {@code 0} if the content length is unknown
   */
  protected abstract void writeAndFlushStatusAndHeaders(final ContainerResponse containerResponse,
                                                        final long contentLength);

  /**
   * Called to create a {@link ChunkedInput} that will be used as part
   * of setting up an {@link OutputStream} for the {@link
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
   * @see ChunkedInput
   */
  protected abstract ChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor,
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
   * @see #failure(Throwable)
   */
  protected abstract void writeFailureMessage();


  /*
   * Utility methods.
   */

  
  protected final boolean inEventLoop() {
    return this.channelHandlerContext.executor().inEventLoop();
  }

  public static final void copyHeaders(final Map<? extends String, ? extends List<String>> headersSource,
                                       final BiConsumer<? super String, ? super List<String>> headersTarget) {
    copyHeaders(headersSource, UnaryOperator.identity(), headersTarget);
  }
  
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