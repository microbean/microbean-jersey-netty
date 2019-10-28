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

/**
 * An {@link InputStream} implementation that {@linkplain
 * #read(byte[], int, int) reads from} a {@link CompositeByteBuf}
 * whose contents are updated via the {@link #addByteBuf(ByteBuf)}
 * method.
 *
 * <p>This class is designed to bridge the gap between a Netty
 * I/O-focused event loop and a thread where Jersey will be reading
 * content.  Reads must block on the Jersey thread, but must not
 * impact the Netty event loop.  Instances of this class are used by
 * {@link AbstractContainerRequestDecoder} implementations, when they
 * need to supply an {@link InputStream} to Jersey so that Jersey can
 * read any incoming entity payloads.</p>
 *
 * <p>The {@link AbstractContainerRequestDecoder} implementation will
 * typically call {@link #addByteBuf(ByteBuf)} on the Netty event
 * loop, and then will call {@link #terminate()} when it is done.
 * Meanwhile, Jersey may call the {@link #read(byte[], int, int)}
 * method on its own thread, and such a call may block at any point
 * until Netty supplies more content.</p>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #addByteBuf(ByteBuf)
 * 
 * @see CompositeByteBuf
 *
 * @see
 * AbstractContainerRequestDecoder#createTerminableByteBufInputStream(ByteBufAllocator)
 */
public final class TerminableByteBufInputStream extends InputStream {

  private static final int OPEN = 0;

  private static final int TERMINATED = 1;

  private static final int CLOSED = 2;

  private volatile int state;
  
  private final CompositeByteBuf byteBuf;

  private final Phaser phaser;

  /**
   * Creates a new {@link TerminableByteBufInputStream}.
   *
   * @param byteBufAllocator a {@link ByteBufAllocator} that will
   * {@linkplain ByteBufAllocator#compositeBuffer() allocate a new
   * <code>CompositeByteBuf</code>} from which {@linkplain
   * #read(byte[], int, int) content may be read by another thread};
   * must not be {@code null}
   *
   * @exception NullPointerException if {@code byteBufAllocator} is
   * {@code null}
   *
   * @see #addByteBuf(ByteBuf)
   *
   * @see #read(byte[], int, int)
   */
  public TerminableByteBufInputStream(final ByteBufAllocator byteBufAllocator) {
    super();
    this.byteBuf = Objects.requireNonNull(byteBufAllocator).compositeBuffer();
    this.phaser = new Phaser(2);
  }

  /**
   * Closes this {@link TerminableByteBufInputStream} and {@linkplain
   * ByteBuf#release() releases its underlying
   * <code>CompositeByteBuf</code>}.
   *
   * @exception IOException if an error occurs
   */
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

  /**
   * Irrevocably disables the {@link #addByteBuf(ByteBuf)} method such
   * that calling it will result in an {@link IllegalStateException}
   * being thrown.
   *
   * @see #addByteBuf(ByteBuf)
   */
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

  /**
   * Returns an estimate of the number of bytes that may be read
   * without blocking.
   *
   * @return an estimate of the number of bytes that may be read
   * without blocking; always {@code 0} or a positive {@code int}
   *
   * @exception IOException if this {@link
   * TerminableByteBufInputStream} has been {@linkplain #close()
   * closed}
   */
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

  /**
   * Reads a single byte from this {@link TerminableByteBufInputStream}.
   *
   * @return the byte read, or {@code -1} if the end of the stream has
   * been reached (for this to happen {@link #terminate()} must have
   * already been called at some point in the past)
   *
   * @exception IOException if this {@link
   * TerminableByteBufInputStream} has been {@linkplain #close()
   * closed}
   *
   * @see #terminate()
   */
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

  /**
   * Calls the {@link #read(byte[], int, int)} method with the
   * supplied {@code targetBytes} array, {@code 0} and {@code
   * targetBytes.length} as arguments and returns its result.
   *
   * @param targetBytes the {@code byte} array into which read {@code
   * byte}s will be written, beginning at index {@code 0}; must not be
   * {@code null}
   *
   * @return the number of {@code byte}s actually read, or {@code -1}
   * if the end of the stream has been reached, in which case {@link
   * #terminate()} must have been called in the past
   *
   * @exception NullPointerException if {@code targetBytes} is {@code
   * null}
   *
   * @exception IOException if this {@link
   * TerminableByteBufInputStream} has been {@link #close() closed}
   *
   * @see #read(byte[], int, int)
   *
   * @see #terminate()
   *
   * @see #addByteBuf(ByteBuf)
   */
  @Override
  public final int read(final byte[] targetBytes) throws IOException {
    return this.read(targetBytes, 0, targetBytes.length);
  }

  /**
   * Attempts to read the desired number of {@code byte}s as indicated
   * by the supplied {@code length} parameter into the supplied {@code
   * targetByte} array, beginning the write at the element in the
   * supplied {@code targetByteArray} designated by the {@code offset}
   * parameter, and returns the actual number of {@code byte}s read,
   * or {@code -1} if no {@code byte}s were read and the end of the
   * stream was reached, in which case {@link #terminate()} must have
   * been called in the past.
   *
   * <p>Content to read is supplied by means of the {@link
   * #addByteBuf(ByteBuf)} method.</p>
   *
   * @param targetByteArray an array of {@code byte}s to which read
   * {@code byte}s will be written, beginning at the element
   * identified by the supplied {@code offset}; must not be {@code null}
   *
   * @param offset the zero-based index of the element within the
   * supplied {@code targetByteArray} that will hold the first {@code
   * byte} read; must be {@code 0} or greater and less than the length
   * property of the supplied {@code targetByteArray}
   *
   * @param length the number of {@code byte}s to read; must be {@code
   * zero} or greater and must be less than or equal to the length
   * property of the supplied {@code targetByteArray} minus the
   * supplied (valid) {@code offset}
   *
   * @return the number of {@code byte}s actually read, or {@code -1}
   * if the end of the stream has been reached, in which case {@link
   * #terminate()} must have been called in the past
   *
   * @exception NullPointerException if {@code targetByteArray} is
   * {@code null}
   *
   * @exception IndexOutOfBoundsException if {@code offset} is less
   * than {@code 0}, or if {@code length} is less than {@code 0}, or
   * if {@code length} is greater than the length property of the
   * supplied {@code targetByteArray} parameter minus the supplied
   * {@code offset}
   *
   * @exception IOException if this {@link
   * TerminableByteBufInputStream} has been {@link #close() closed}
   *
   * @see #terminate()
   *
   * @see #addByteBuf(ByteBuf)
   */
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

  /**
   * Adds content for this {@link TerminableByteBufInputStream} to
   * {@linkplain #read(byte[], int, int) read}.
   *
   * @param byteBuf a {@link ByteBuf}; must not be {@code null} and
   * must be (initially) {@link ByteBuf#isReadable() readable}
   *
   * @exception NullPointerException if {@code byteBuf} is {@code
   * null}
   *
   * @exception IllegalArgumentException if {@code byteBuf} is
   * {@linkplain ByteBuf#isReadable() not readable}
   *
   * @exception IllegalStateException if this {@link
   * TerminableByteBufInputStream} is {@link #close() closed} or
   * {@linkplain #terminate() terminated}
   *
   * @see #terminate()
   *
   * @see #read(byte[], int, int)
   */
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
