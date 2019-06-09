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

public final class ByteBufChunkedInput implements ChunkedInput<ByteBuf> {

  private final ByteBuf byteBuf;

  private volatile boolean closed;
  
  public ByteBufChunkedInput(final ByteBuf byteBuf) {
    super();
    this.byteBuf = Objects.requireNonNull(byteBuf);
  }

  @Override
  public final boolean isEndOfInput() throws Exception {
    return this.byteBuf.refCnt() <= 0 || (this.closed && !this.byteBuf.isReadable());
  }

  @Deprecated
  @Override
  public final ByteBuf readChunk(final ChannelHandlerContext channelHandlerContext) throws Exception {
    return this.readChunk(channelHandlerContext.alloc());
  }

  @Override
  public final ByteBuf readChunk(final ByteBufAllocator ignoredByteBufAllocator) throws Exception {
    return this.byteBuf.refCnt() <= 0 || (this.closed && !this.byteBuf.isReadable()) ? null : this.byteBuf.readRetainedSlice(this.byteBuf.readableBytes()).asReadOnly();
  }

  @Override
  public final long length() {
    return -1;
  }

  @Override
  public final long progress() {
    // e.g. we've read <progress> of <length> bytes.  Other
    // ChunkedInput implementations return a valid number here even
    // when length() returns -1, so we do too.
    return this.byteBuf.readerIndex();
  }

  @Override
  public final void close() {
    this.closed = true;
  }

}
