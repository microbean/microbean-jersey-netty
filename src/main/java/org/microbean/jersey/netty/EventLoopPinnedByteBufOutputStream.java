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

import java.util.Objects;

import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * An {@link OutputStream} that writes to a {@link ByteBuf} on Netty's
 * event loop.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #write(byte[], int, int)
 */
public final class EventLoopPinnedByteBufOutputStream extends OutputStream {


  /*
   * Instance fields.
   */


  private final EventExecutor eventExecutor;

  private final ByteBuf target;

  private final GenericFutureListener<? extends Future<? super Void>> listener;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link EventLoopPinnedByteBufOutputStream}.
   *
   * @param eventExecutor an {@link EventExecutor} that will be used
   * to {@linkplain EventExecutor#inEventLoop() ensure all operations
   * are executed on the Netty event loop}; must not be {@code null}
   *
   * @param target the {@link ByteBuf} that will be {@linkplain
   * ByteBuf#writeBytes(byte[], int, int) written to}; must not be
   * {@code null}
   *
   * @param listener a {@link GenericFutureListener} that {@linkplain
   * GenericFutureListener#operationComplete(Future) will be notified
   * when a <code>Future</code> representing a <code>ByteBuf</code>
   * write operation completes on the Netty event loop}; may be {@code
   * null}
   *
   * @exception NullPointerException if {@code eventExecutor} or
   * {@code target} is {@code null}
   */
  public EventLoopPinnedByteBufOutputStream(final EventExecutor eventExecutor,
                                            final ByteBuf target,
                                            final GenericFutureListener<? extends Future<? super Void>> listener) {
    super();
    this.eventExecutor = Objects.requireNonNull(eventExecutor);
    this.target = Objects.requireNonNull(target);
    this.listener = listener;
  }


  /*
   * Instance methods.
   */


  @Override
  public final void write(final byte[] bytes) {
    Objects.requireNonNull(bytes);
    this.perform(bb -> bb.writeBytes(bytes));
  }

  @Override
  public final void write(final byte[] bytes, final int offset, final int length) {
    Objects.requireNonNull(bytes);
    if (offset < 0 || length < 0 || offset + length > bytes.length) {
      throw new IndexOutOfBoundsException();
    }
    this.perform(bb -> bb.writeBytes(bytes, offset, length));
  }

  @Override
  public final void write(final int b) {
    this.perform(bb -> bb.writeByte(b));
  }

  private final void perform(final Consumer<? super ByteBuf> byteBufWriter) {
    if (this.eventExecutor.inEventLoop()) {
      byteBufWriter.accept(this.target);
    } else {
      final Future<Void> byteBufWriterFuture = this.eventExecutor.submit(() -> {
          byteBufWriter.accept(this.target);
          return null;
        });
      assert byteBufWriterFuture != null;
      if (this.listener != null) {
        byteBufWriterFuture.addListener(this.listener);
      }
    }
  }

}
