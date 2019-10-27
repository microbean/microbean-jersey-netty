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

import java.util.concurrent.Phaser;

import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

public final class TerminableByteBufInputStream extends InputStream {

  private static final int OPEN = 0;

  private static final int TERMINATED = 1;

  private static final int CLOSED = 2;

  private volatile int state;
  
  private final CompositeByteBuf byteBuf;

  private final Phaser phaser;

  public TerminableByteBufInputStream(final ByteBufAllocator byteBufAllocator) {
    super();
    this.byteBuf = Objects.requireNonNull(byteBufAllocator).compositeBuffer();
    this.phaser = new Phaser(2);
  }

  @Override
  public final void close() throws IOException {
    final int state = this.state;
    switch (state) {
    case CLOSED:
      break;
    case TERMINATED:
      // fall through
    case OPEN:
      try {
        assert this.byteBuf.refCnt() == 1;
        // No need to synchronize; release() works on a volatile int
        final boolean released = this.byteBuf.release();
        assert released;
      } finally {
        this.state = CLOSED;
        this.phaser.forceTermination();
      }
      break;
    default:
      throw new IOException("Unexpected state: " + state);
    }
  }

  public final void terminate() {
    final int state = this.state;
    switch (state) {
    case CLOSED:
      break;
    case TERMINATED:
      break;
    case OPEN:
      this.state = TERMINATED;
      this.phaser.forceTermination();
      break;
    default:
      throw new IllegalStateException("Unexpected state: " + state);
    }
  }

  @Override
  public final int available() throws IOException {
    final int state = this.state;
    switch (state) {
    case CLOSED:
      throw new IOException("closed");
    case TERMINATED:
      // No further writes will happen so no synchronization needed.
      return this.byteBuf.readableBytes();
    case OPEN:
      synchronized (this.byteBuf) {
        return this.byteBuf.readableBytes();
      }
    default:
      throw new IOException("Unexpected state: " + state);
    }
  }

  @Override
  public final int read() throws IOException {
    final int state = this.state;
    switch (state) {
    case CLOSED:
      throw new IOException("closed");
    case TERMINATED:
      // fall through
    case OPEN:
      return this.read(sourceByteBuf -> Integer.valueOf(sourceByteBuf.readByte()));
    default:
      throw new IOException("Unexpected state: " + state);
    }
  }

  @Override
  public final int read(final byte[] targetBytes) throws IOException {
    return this.read(targetBytes, 0, targetBytes.length);
  }

  @Override
  public final int read(final byte[] targetByteArray, final int offset, final int length) throws IOException {
    if (offset < 0 || length < 0 || length > targetByteArray.length - offset) {
      throw new IndexOutOfBoundsException();
    }
    final int state = this.state;
    switch (state) {
    case CLOSED:
      throw new IOException("closed");
    case TERMINATED:
      // fall through
    case OPEN:
      // Synchronization will be handled by #read(Function)
      return length == 0 ? 0 : this.read(sourceByteBuf -> {
          final int readThisManyBytes = Math.min(length, sourceByteBuf.readableBytes());
          sourceByteBuf.readBytes(targetByteArray, offset, readThisManyBytes);
          return Integer.valueOf(readThisManyBytes);
        });
    default:
      throw new IOException("Unexpected state: " + state);
    }
  }

  public final void addByteBuf(final ByteBuf byteBuf) {
    Objects.requireNonNull(byteBuf);
    if (!byteBuf.isReadable()) {
      // Prevent adds of empty ByteBufs as much as we can.
      throw new IllegalArgumentException("!byteBuf.isReadable()");
    }
    final int state = this.state;
    switch (state) {
    case CLOSED:
      throw new IllegalStateException("closed");
    case TERMINATED:
      throw new IllegalStateException("terminated");
    case OPEN:
      synchronized (this.byteBuf) {
        this.byteBuf.addComponent(true /* advance the writerIndex */, byteBuf);
      }
      this.phaser.arrive(); // (Nonblocking)
      break;
    default:
      throw new IllegalStateException("Unexected state: " + state);
    }
  }

  private final int read(final Function<? super ByteBuf, ? extends Integer> function) throws IOException {
    Objects.requireNonNull(function);
    int state = this.state;
    switch (state) {
    case CLOSED:
      throw new IOException("closed");
    case TERMINATED:
      // No further writes will happen so no synchronization needed.
      return this.byteBuf.isReadable() ? function.apply(this.byteBuf) : -1;
    case OPEN:
      do {
        assert state == OPEN;
        synchronized (this.byteBuf) {
          if (this.byteBuf.isReadable()) {
            break;
          }
        }
        this.phaser.awaitAdvance(this.phaser.arrive()); // BLOCKING
      } while ((state = this.state) == OPEN);
      // We unblocked (or maybe never blocked in the first place).
      // This is either because our state changed to something that is
      // not OPEN or our CompositeByteBuf became readable. Check state
      // first.
      switch (state) {
      case CLOSED:
        throw new IOException("closed");
      case TERMINATED:
        // No further writes will happen so no synchronization needed.
        return this.byteBuf.isReadable() ? function.apply(this.byteBuf) : -1;
      case OPEN:
        synchronized (this.byteBuf) {
          return function.apply(this.byteBuf);
        }
      default:
        throw new IOException("Unexpected state: " + state);
      }
    default:
      throw new IOException("Unexpected state: " + state);
    }
  }
  
}
