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

import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.stream.ChunkedInput;

import io.netty.util.concurrent.EventExecutor;

public class ByteBufChunkedInput implements ChunkedInput<ByteBuf> {

  private final EventExecutor eventExecutor;
  
  private final ByteBuf byteBuf;

  private volatile boolean closed;
  
  public ByteBufChunkedInput(final EventExecutor eventExecutor,
                             final ByteBuf byteBuf) {
    super();
    this.eventExecutor = Objects.requireNonNull(eventExecutor);
    this.byteBuf = Objects.requireNonNull(byteBuf);
  }

  @Override
  public boolean isEndOfInput() throws Exception {
    // It is never the end of the input until the writer is closed.
    // This is because the underlying ByteBuf can grow at will, and
    // can be written to at will.
    assert this.eventExecutor.inEventLoop();
    return this.closed;
  }

  @Deprecated
  @Override
  public final ByteBuf readChunk(final ChannelHandlerContext channelHandlerContext) throws Exception {
    return this.readChunk(channelHandlerContext.alloc());
  }

  @Override
  public ByteBuf readChunk(final ByteBufAllocator ignoredByteBufAllocator) throws Exception {
    assert this.eventExecutor.inEventLoop();
    assert this.byteBuf != null;
    return this.closed ? null : this.byteBuf.readSlice(this.byteBuf.readableBytes()).asReadOnly();
  }

  @Override
  public final long length() {
    assert this.eventExecutor.inEventLoop();
    // It is undocumented but ChunkedNioStream returns -1 to indicate,
    // apparently, no idea of the length.  We don't have any idea
    // either, or at any rate, it could be changing.
    return -1;
  }

  @Override
  public final long progress() {
    assert this.eventExecutor.inEventLoop();
    assert this.byteBuf != null;
    // e.g. we've read <progress> of <length> bytes.  Other
    // ChunkedInput implementations return a valid number here even
    // when length() returns -1, so we do too.
    return this.byteBuf.readerIndex();
  }

  @Override
  public final void close() {
    this.closed = true;
    // TODO: this actually seems to get auto-released?
    // So this may not be necessary?
    final ByteBuf byteBuf = this.byteBuf;
    if (byteBuf != null) {
      byteBuf.release();
    }
  }

}
