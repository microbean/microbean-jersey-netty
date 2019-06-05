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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Phaser;

import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link InputStream} that {@linkplain #read() reads} from a
 * {@link CompositeByteBuf}, ensuring that the read occurs only
 * {@linkplain EventExecutor#inEventLoop() on the Netty event loop
 * thread}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public class EventLoopPinnedByteBufInputStream extends InputStream implements ByteBufQueue {

  private final CompositeByteBuf byteBuf;

  private final EventExecutor eventExecutor;

  private final Phaser phaser;

  private volatile boolean closed;

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
  public final void close() throws IOException {
    // Can be called from the event loop or not.
    this.closed = true;
    this.release();
    super.close();
  }

  private final void release() {
    // Can be called from the event loop or not.
    if (!this.closed) {
      throw new IllegalStateException("!this.closed");
    }
    if (eventExecutor.inEventLoop()) {
      this.byteBuf.release();
    } else {
      this.eventExecutor.execute(() -> this.byteBuf.release());
    }
  }

  @Override
  public final int read() throws IOException {
    // Shouldn't be in the event loop.
    if (this.closed) {
      throw new IOException();
    }
    return this.read(sourceByteBuf -> Integer.valueOf(sourceByteBuf.readByte()));
  }

  @Override
  public final int read(final byte[] targetBytes) throws IOException {
    return this.read(targetBytes, 0, targetBytes.length);
  }

  @Override
  public final int read(final byte[] targetBytes, final int offset, final int length) throws IOException {
    // Shouldn't be in the event loop.
    if (this.closed) {
      throw new IOException();
    }
    return this.read(sourceByteBuf -> {
        final int readThisManyBytes = Math.min(length, sourceByteBuf.readableBytes());
        sourceByteBuf.readBytes(targetBytes, offset, readThisManyBytes);
        return Integer.valueOf(readThisManyBytes);
      });
  }


  /*
   * ByteBufQueue methods.  These must be invoked while on the Netty
   * event loop.
   */


  public final void addByteBuf(final ByteBuf byteBuf) {
    Objects.requireNonNull(byteBuf);
    if (!this.eventExecutor.inEventLoop()) {
      throw new IllegalStateException("!(this.eventExecutor.inEventLoop(): " + Thread.currentThread());
    }
    this.byteBuf.addComponent(byteBuf);
    this.phaser.arrive(); // (Nonblocking)
  }


  /*
   * Protected methods.
   */


  /**
   * {@linkplain Function#apply(Object) Applies} supplied {@link
   * Function} to a {@link ByteBuf}, ensuring that the {@link
   * Function} application takes place on the {@linkplain
   * EventExecutor#inEventLoop() Netty event loop thread}, and returns
   * the {@link Function}'s return value.
   */
  protected final int read(final Function<? super ByteBuf, ? extends Integer> function) throws IOException {
    Objects.requireNonNull(function);
    final CompositeByteBuf myByteBuf = this.byteBuf;
    assert myByteBuf != null;
    final int phaseNumber = this.phaser.arrive();
    while (myByteBuf.numComponents() <= 0) {
      this.phaser.awaitAdvance(phaseNumber); // BLOCKING
    }
    final FutureTask<Integer> readTask = new FutureTask<>(new EventLoopByteBufOperation(this.eventExecutor, myByteBuf, function));
    if (this.eventExecutor.inEventLoop()) {
      readTask.run();
    } else {
      this.eventExecutor.execute(readTask);
    }
    Integer returnValue = null;
    try {
      returnValue = readTask.get();
    } catch (final ExecutionException executionException) {
      final Throwable cause = executionException.getCause();
      if (cause instanceof IOException) {
        throw (IOException)cause;
      } else if (cause instanceof RuntimeException) {
        throw (RuntimeException)cause;
      } else {
        throw new IOException(executionException.getMessage(), executionException);
      }
    } catch (final InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new IOException(interruptedException.getMessage(), interruptedException);
    }
    assert returnValue != null;
    return returnValue.intValue();
  }

  private static final class EventLoopByteBufOperation implements Callable<Integer> {

    private final EventExecutor eventExecutor;

    private final ByteBuf byteBuf;

    private final Function<? super ByteBuf, ? extends Integer> function;

    private EventLoopByteBufOperation(final EventExecutor eventExecutor,
                                      final ByteBuf byteBuf,
                                      final Function<? super ByteBuf, ? extends Integer> function) {
      super();
      this.eventExecutor = Objects.requireNonNull(eventExecutor);
      this.byteBuf = Objects.requireNonNull(byteBuf);
      this.function = Objects.requireNonNull(function);
    }

    @Override
    public final Integer call() {
      assert this.eventExecutor.inEventLoop();
      return this.byteBuf.isReadable() ? function.apply(this.byteBuf) : Integer.valueOf(-1);
    }

  }

}
