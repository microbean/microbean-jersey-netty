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


  /*
   * Constructors.
   */

  
  /**
   * Creates a new {@link EventLoopPinnedByteBufInputStream}.
   *
   * @param compositeByteBuf the {@link CompositeByteBuf} that will be
   * {@linkplain CompositeByteBuf#readBytes(byte[], int, int) read}
   * from; must not be {@code null}
   *
   * @param eventExecutor an {@link EventExecutor} that will
   * {@linkplain EventExecutor#inEventLoop() ensure operations occur
   * on the Netty event loop}; must not be {@code null}
   *
   * @exception NullPointerException if either {@code
   * compositeByteBuf} or {@code eventExecutor} is {@code null}
   */
  public EventLoopPinnedByteBufInputStream(final CompositeByteBuf compositeByteBuf,
                                           final EventExecutor eventExecutor) {
    super();
    this.phaser = new Phaser(2);
    this.byteBuf = Objects.requireNonNull(compositeByteBuf);
    this.eventExecutor = Objects.requireNonNull(eventExecutor);
  }


  /*
   * InputStream overrides.
   */


  @Override
  public final int read() throws IOException {
    return this.read(sourceByteBuf -> Integer.valueOf(sourceByteBuf.readByte()));
  }

  @Override
  public final int read(final byte[] targetBytes) throws IOException {
    return this.read(targetBytes, 0, targetBytes.length);
  }

  @Override
  public final int read(final byte[] targetBytes, final int offset, final int length) throws IOException {
    Objects.requireNonNull(targetBytes);
    final int returnValue;
    if (offset < 0 || length < 0 || length > targetBytes.length - offset) {
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
    if (byteBuf != this.byteBuf) {
      if (this.eventExecutor.inEventLoop()) {
        this.byteBuf.addComponent(true /* advance the writerIndex */, byteBuf);
      } else {
        this.eventExecutor.execute(() -> this.byteBuf.addComponent(true /* advance the writerIndex */, byteBuf));
      }
    }
    this.phaser.arrive(); // (Nonblocking)
  }


  /*
   * Protected methods.
   */


  /**
   * {@linkplain Function#apply(Object) Applies} the supplied {@link
   * Function} to a {@link ByteBuf}, ensuring that the {@link
   * Function} application takes place on the {@linkplain
   * EventExecutor#inEventLoop() Netty event loop thread}, and returns
   * the result of invoking {@link Integer#intValue()} on the {@link
   * Function}'s return value.
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
   * Function} could not be acquired for some reason
   */
  protected final int read(final Function<? super ByteBuf, ? extends Integer> function) throws IOException {
    Objects.requireNonNull(function);
    final int phaseNumber = this.phaser.arrive();
    while (this.byteBuf.numComponents() <= 0) {
      this.phaser.awaitAdvance(phaseNumber); // BLOCKING
    }
    final FutureTask<Integer> readTask = new FutureTask<>(new EventLoopByteBufOperation(this.byteBuf, function));
    if (this.eventExecutor.inEventLoop()) {
      readTask.run();
    } else {
      this.eventExecutor.execute(readTask);
    }
    Integer returnValue = null;
    try {
      returnValue = readTask.get(); // BLOCKING
    } catch (final ExecutionException executionException) {
      final Throwable cause = executionException.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException)cause;
      } else if (cause instanceof Error) {
        throw (Error)cause;
      } else {
        // This should be prevented by the compiler:
        // EventLoopByteBufOperation#call() does not throw any checked
        // exceptions.
        throw new InternalError(cause.getMessage(), cause);
      }
    } catch (final InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new IOException(interruptedException.getMessage(), interruptedException);
    }
    if (returnValue == null) {
      throw new IOException("function.apply() == null");
    }
    return returnValue.intValue();
  }


  /*
   * Inner and nested classes.
   */

  
  private static final class EventLoopByteBufOperation implements Callable<Integer> {

    private final ByteBuf byteBuf;

    private final Function<? super ByteBuf, ? extends Integer> function;

    private EventLoopByteBufOperation(final ByteBuf byteBuf,
                                      final Function<? super ByteBuf, ? extends Integer> function) {
      super();
      this.byteBuf = Objects.requireNonNull(byteBuf);
      this.function = Objects.requireNonNull(function);
    }

    @Override
    public final Integer call() {
      return this.byteBuf.refCnt() > 0 && this.byteBuf.isReadable() ? function.apply(this.byteBuf) : Integer.valueOf(-1);
    }

  }

}
