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

import java.io.InputStream;
import java.io.IOException;

import java.util.Objects;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Phaser;

import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link ByteBufQueue} and an {@link InputStream} that {@linkplain
 * #read() reads} from a (possibly mutating and {@linkplain
 * CompositeByteBuf#addComponent(boolean, ByteBuf) expanding}) {@link
 * CompositeByteBuf}, ensuring that all operations occur only
 * {@linkplain EventExecutor#inEventLoop() on the Netty event loop
 * thread}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #read(byte[], int, int)
 *
 * @see #read(Function)
 */
public class EventLoopPinnedByteBufInputStream extends InputStream implements ByteBufQueue {


  /*
   * Instance fields.
   */


  private final CompositeByteBuf byteBuf;

  private final EventExecutor eventExecutor;

  private final Phaser phaser;

  private volatile boolean closed;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link EventLoopPinnedByteBufInputStream}.
   *
   * @param byteBufAllocator a {@link ByteBufAllocator} that will be
   * used to allocate an internal {@link CompositeByteBuf} of some
   * variety; must not be {@code null}
   *
   * @param eventExecutor an {@link EventExecutor} that will
   * {@linkplain EventExecutor#inEventLoop() ensure operations occur
   * on the Netty event loop}; must not be {@code null}
   *
   * @exception NullPointerException if either {@code
   * byteBufAllocator} or {@code eventExecutor} is {@code null}
   */
  public EventLoopPinnedByteBufInputStream(final ByteBufAllocator byteBufAllocator,
                                           final EventExecutor eventExecutor) {
    super();
    Objects.requireNonNull(byteBufAllocator);
    this.eventExecutor = Objects.requireNonNull(eventExecutor);
    this.byteBuf = byteBufAllocator.compositeBuffer();
    if (this.byteBuf == null) {
      throw new IllegalArgumentException("byteBufAllocator.compositeBuffer() == null");
    }
    this.phaser = new Phaser(2);
  }


  /*
   * InputStream overrides.
   */


  /**
   * Closes this {@link EventLoopPinnedByteBufInputStream}.
   */
  @Override
  public final void close() {
    if (!this.closed) {
      if (this.eventExecutor.inEventLoop()) {
        try {
          assert this.byteBuf.refCnt() == 1;
          final boolean released = this.byteBuf.release();
          assert released;
        } finally {
          this.closed = true;
          this.phaser.forceTermination();
        }
      } else {
        this.eventExecutor.execute(() -> {
            if (!this.closed) {
              try {
                assert this.byteBuf.refCnt() == 1;
                final boolean released = this.byteBuf.release();
                assert released;
              } finally {
                this.closed = true;
                this.phaser.forceTermination();
              }
            }
          });
      }
    }
  }

  @Override
  public final int read() throws IOException {
    if (this.closed) {
      throw new IOException("closed");
    }
    return this.read(sourceByteBuf -> Integer.valueOf(sourceByteBuf.readByte()));
  }

  @Override
  public final int read(final byte[] targetBytes) throws IOException {
    Objects.requireNonNull(targetBytes);
    if (this.closed) {
      throw new IOException("closed");
    }
    return this.read(targetBytes, 0, targetBytes.length);
  }

  @Override
  public final int read(final byte[] targetBytes, final int offset, final int length) throws IOException {
    Objects.requireNonNull(targetBytes);
    final int returnValue;
    if (this.closed) {
      throw new IOException("closed");
    } else if (offset < 0 || length < 0 || length > targetBytes.length - offset) {
      throw new IndexOutOfBoundsException();
    } else if (length == 0) {
      returnValue = 0;
    } else {
      returnValue = this.read(sourceByteBuf -> {
          final int readThisManyBytes = Math.min(length, sourceByteBuf.readableBytes());
          sourceByteBuf.readBytes(targetBytes, offset, readThisManyBytes);
          return Integer.valueOf(readThisManyBytes);
        });
    }
    return returnValue;
  }


  /*
   * ByteBufQueue methods.
   */


  /**
   * {@linkplain CompositeByteBuf#addComponent(boolean, ByteBuf) Adds}
   * the supplied {@link ByteBuf} to the {@link CompositeByteBuf}
   * maintained by this {@link EventLoopPinnedByteBufInputStream}.
   *
   * @param byteBuf the {@link ByteBuf} to add; must not be {@code
   * null}
   *
   * @exception NullPointerException if {@code byteBuf} is {@code
   * null}
   */
  public final void addByteBuf(final ByteBuf byteBuf) {
    Objects.requireNonNull(byteBuf);
    if (!this.closed && byteBuf != this.byteBuf) {
      if (this.eventExecutor.inEventLoop()) {
        this.byteBuf.addComponent(true /* advance the writerIndex */, byteBuf);
        this.phaser.arrive(); // (Nonblocking)
      } else {
        this.eventExecutor.execute(() -> {
            this.byteBuf.addComponent(true /* advance the writerIndex */, byteBuf);
            this.phaser.arrive(); // (Nonblocking)
          });
      }
    }
  }


  /*
   * Protected methods.
   */


  /**
   * {@linkplain Function#apply(Object) Applies} the supplied {@link
   * Function} to a {@link ByteBuf}, ensuring that the {@link
   * Function} application takes place on the {@linkplain
   * EventExecutor#inEventLoop() Netty event loop thread}, and,
   * <strong>blocking if necessary</strong>, returns the result of
   * invoking {@link Integer#intValue()} on the {@link Function}'s
   * return value.
   *
   * @param function the {@link Function} to apply; must not be {@code
   * null}; must not return {@code null}
   *
   * @return an {@code int} resulting from the {@link Function}
   * application
   *
   * @exception NullPointerException if {@code function} is {@code null}
   *
   * @exception IOException if the return value of the supplied {@link
   * Function} could not be acquired for some reason, or if this
   * {@link EventLoopPinnedByteBufInputStream} has been {@linkplain
   * #close() closed}; if its {@linkplain Throwable#getCause() cause}
   * is an {@link InterruptedException} then the current thread has
   * been interrupted
   */
  protected final int read(final Function<? super ByteBuf, ? extends Integer> function) throws IOException {
    Objects.requireNonNull(function);
    if (this.closed) {
      throw new IOException("closed");
    }
    final int phaseNumber = this.phaser.arrive();
    while (!this.closed && this.byteBuf.numComponents() <= 0) {
      this.phaser.awaitAdvance(phaseNumber); // BLOCKING
    }
    Integer returnValue = null;
    if (this.closed) {
      // We don't throw an exception here, because the read() call was
      // already invoked while we were still not closed.  Instead,
      // this indicates end-of-stream semantics.
      returnValue = Integer.valueOf(-1);
    } else {
      final FutureTask<Integer> readTask = new FutureTask<>(new EventLoopByteBufOperation(function));
      if (this.eventExecutor.inEventLoop()) {
        readTask.run();
      } else {
        this.eventExecutor.execute(readTask);
      }
      try {
        returnValue = readTask.get(); // BLOCKING
      } catch (final ExecutionException executionException) {
        final Throwable cause = executionException.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException)cause;
        } else if (cause instanceof IOException) {
          throw (IOException)cause;
        } else if (cause instanceof Error) {
          throw (Error)cause;
        } else {
          // This should be prevented by the compiler:
          // EventLoopByteBufOperation#call() does not throw any other
          // checked exceptions.
          throw new InternalError(cause.getMessage(), cause);
        }
      } catch (final InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        throw new IOException(interruptedException.getMessage(), interruptedException);
      }
    }
    if (returnValue == null) {
      throw new IOException("function.apply() == null", new IllegalStateException("function.apply() == null"));
    }
    return returnValue.intValue();
  }


  /*
   * Inner and nested classes.
   */


  private final class EventLoopByteBufOperation implements Callable<Integer> {

    private final Function<? super ByteBuf, ? extends Integer> function;

    private EventLoopByteBufOperation(final Function<? super ByteBuf, ? extends Integer> function) {
      super();
      this.function = Objects.requireNonNull(function);
    }

    @Override
    public final Integer call() throws IOException {
      if (closed) {
        throw new IOException("closed");
      }
      assert byteBuf.refCnt() == 1;
      // The byteBuf may be expanding, of course, but we can safely
      // return -1 here if it is not readable because the
      // #read(Function) method blocks to ensure there are other
      // components to read.  In other words, non-readability here is
      // a definitive indication of end-of-stream semantics, so -1 is
      // appropriate as a return value in such a case.
      return byteBuf.isReadable() ? function.apply(byteBuf) : Integer.valueOf(-1);
    }

  }

}
