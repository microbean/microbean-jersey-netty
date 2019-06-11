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

import java.io.IOException;
import java.io.OutputStream;

import java.util.Objects;

import io.netty.buffer.ByteBuf;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link OutputStream} whose operations occur on Netty's event
 * loop.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public final class EventLoopPinnedByteBufOutputStream extends OutputStream {

  private final EventExecutor eventExecutor;

  private final ByteBuf byteBuf;

  /**
   * Creates a new {@link EventLoopPinnedByteBufOutputStream}.
   *
   * @param eventExecutor an {@link EventExecutor} that will be used
   * to {@linkplain EventExecutor#inEventLoop() ensure all operations
   * are executed on the Netty event loop}; must not be {@code null}
   *
   * @param byteBuf the {@link ByteBuf} that will be {@linkplain
   * ByteBuf#writeBytes(byte[], int, int) written to}; must not be
   * {@code null}
   */
  public EventLoopPinnedByteBufOutputStream(final EventExecutor eventExecutor,
                                            final ByteBuf byteBuf) {
    super();
    this.eventExecutor = Objects.requireNonNull(eventExecutor);
    this.byteBuf = Objects.requireNonNull(byteBuf);
  }

  @Override
  public final void write(final byte[] bytes) throws IOException {
    this.perform(bb -> bb.writeBytes(bytes));
  }

  @Override
  public final void write(final byte[] bytes, final int offset, final int length) throws IOException {
    this.perform(bb -> bb.writeBytes(bytes, offset, length));
  }

  @Override
  public final void write(final int b) throws IOException {
    this.perform(bb -> bb.writeByte(b));
  }

  private final void perform(final ByteBufOperation byteBufOperation) throws IOException {
    if (this.eventExecutor.inEventLoop()) {
      byteBufOperation.applyTo(this.byteBuf);
    } else {
      this.eventExecutor.submit(() -> {
          byteBufOperation.applyTo(this.byteBuf);
          return null;
        });
    }
  }

}
