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

public class EventLoopPinnedByteBufOutputStream extends OutputStream {

  private final EventExecutor eventExecutor;
  
  private final ByteBuf byteBuf;

  public EventLoopPinnedByteBufOutputStream(final EventExecutor eventExecutor,
                                            final ByteBuf byteBuf) {
    super();
    this.eventExecutor = Objects.requireNonNull(eventExecutor);
    this.byteBuf = Objects.requireNonNull(byteBuf);
  }

  @Override
  public final void write(final byte[] bytes) throws IOException {
    this.perform(bb -> {
        bb.writeBytes(bytes);
      });
  }

  @Override
  public final void write(final byte[] bytes, final int offset, final int length) throws IOException {
    this.perform(bb -> {
        bb.writeBytes(bytes, offset, length);
      });
  }

  @Override
  public final void write(final int b) throws IOException {
    this.perform(bb -> {
        bb.writeByte(b);
      });
  }

  private final void perform(final ByteBufOperation byteBufOperation) throws IOException {
    final ByteBuf byteBuf = this.byteBuf;
    if (this.eventExecutor.inEventLoop()) {
      byteBufOperation.applyTo(byteBuf);
    } else {
      this.eventExecutor.submit(() -> {
          byteBufOperation.applyTo(byteBuf);
          return null;
        });
    }
  }

  private final boolean inEventLoop() {
    return this.eventExecutor.inEventLoop();
  }

}
