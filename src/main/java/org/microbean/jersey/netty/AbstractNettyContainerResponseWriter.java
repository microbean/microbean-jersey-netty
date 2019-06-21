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

import org.glassfish.jersey.spi.ScheduledExecutorServiceProvider;

public abstract class AbstractNettyContainerResponseWriter<T> implements ContainerResponseWriter {


  /*
   * Instance fields.
   */

  protected final T requestObject;

  protected final ChannelHandlerContext channelHandlerContext;

  private final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier;

  private volatile ScheduledFuture<?> suspendTimeoutFuture;

  private volatile Runnable suspendTimeoutHandler;

  private volatile ChunkedInput<?> chunkedInput;

  private volatile ReferenceCounted byteBuf;


  /*
   * Constructors.
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
  public OutputStream writeResponseStatusAndHeaders(final long contentLength, final ContainerResponse containerResponse) throws ContainerException {
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
      final ChunkedInput<?> chunkedInput = this.chunkedInput;
      this.chunkedInput = null;
      final ReferenceCounted byteBuf = this.byteBuf;
      this.byteBuf = null;
      if (chunkedInput != null) {
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
      }
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

  
  protected abstract boolean needsOutputStream(final long contentLength);

  protected abstract void writeLastContentMessage();
  
  protected abstract void writeAndFlushStatusAndHeaders(final ContainerResponse containerResponse,
                                                        final long contentLength);

  protected abstract ChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor,
                                                        final ByteBuf source,
                                                        final long contentLength);
  
  protected abstract void writeFailureMessage();


  /*
   * Utility methods.
   */

  
  protected final boolean inEventLoop() {
    return this.channelHandlerContext.executor().inEventLoop();
  }

  protected static final void transferHeaders(final Map<? extends String, ? extends List<String>> headersSource,
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
