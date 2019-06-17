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

/**
 * A {@link ChunkedInput} implementation that reads from a {@link
 * ByteBuf}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances of this class are safe for concurrent use by multiple
 * threads, but the operations performed on the {@link ByteBuf}
 * instance retained by this class are <em>not</em> guaranteed to be
 * threadsafe.  <strong>This class assumes that it will be invoked on
 * Netty's event loop thread.</strong> Invoking it on any thread other
 * than Netty's event loop thread may result in undefined
 * behavior.</p>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public class ByteBufChunkedInput implements ChunkedInput<ByteBuf> {


  /*
   * Instance fields.
   */


  /**
   * The {@link ByteBuf} from which to read.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #ByteBufChunkedInput(ByteBuf)
   */
  private final ByteBuf byteBuf;

  /**
   * The value to return from the default implementation of the {@link
   * #length()} method.
   *
   * <p>This field is never less than {@code -1L}.
   *
   * @see #length()
   */
  private final long lengthToReport;

  /**
   * Indicates that no more input is forthcoming, so assuming other
   * conditions are true the {@link #isEndOfInput()} method may return
   * {@code true}.
   *
   * @see #isEndOfInput()
   *
   * @see #close()
   */
  private volatile boolean closed;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link ByteBufChunkedInput} whose {@link #length()}
   * method will return {@code -1L}.
   *
   * @param byteBuf the {@link ByteBuf} from which chunks will be
   * {@linkplain #readChunk(ByteBufAllocator) read}; must not be
   * {@code null}
   *
   * @see #ByteBufChunkedInput(ByteBuf, long)
   */
  public ByteBufChunkedInput(final ByteBuf byteBuf) {
    this(byteBuf, -1L);
  }

  /**
   * Creates a new {@link ByteBufChunkedInput}.
   *
   * @param byteBuf the {@link ByteBuf} from which chunks will be
   * {@linkplain #readChunk(ByteBufAllocator) read}; must not be
   * {@code null}
   *
   * @param lengthToReport the length of this {@link ChunkedInput}
   * implementation in bytes to be reported by the {@link #length()}
   * method; may be {@code -1L} (and very often is) if a
   * representation of its length is not available.
   *
   * @exception NullPointerException if {@code byteBuf} is {@code
   * null}
   */
  public ByteBufChunkedInput(final ByteBuf byteBuf, final long lengthToReport) {
    super();
    this.byteBuf = Objects.requireNonNull(byteBuf);
    this.lengthToReport = Math.max(-1L, lengthToReport);
  }


  /*
   * Instance methods.
   */


  /**
   * Returns {@code true} if the end of the input has been reached and
   * subsequent invocations of the {@link
   * #readChunk(ByteBufAllocator)} method will return {@code
   * null}.
   *
   * @return {@code true} if the end of the input has been reached and
   * subsequent invocations of the {@link
   * #readChunk(ByteBufAllocator)} method will return {@code
   * null}.
   */
  @Override
  public final boolean isEndOfInput() {
    return this.byteBuf.refCnt() <= 0 || (this.closed && !this.byteBuf.isReadable());
  }

  /**
   * Calls the {@link #readChunk(ByteBufAllocator)} method and returns
   * its result.
   *
   * <p>This method may and often does return {@code null}.</p>
   *
   * @param channelHandlerContext a {@link ChannelHandlerContext}
   * whose {@link ChannelHandlerContext#alloc()} method is invoked to
   * acquire a {@link ByteBufAllocator}; may be {@code null}
   *
   * @return a {@link ByteBuf} representing a chunk of the overall
   * data, or {@code null}
   *
   * @see #readChunk(ByteBufAllocator)
   *
   * @deprecated Please use the {@link #readChunk(ByteBufAllocator)}
   * method instead.
   */
  @Deprecated
  @Override
  public final ByteBuf readChunk(final ChannelHandlerContext channelHandlerContext) {
    return this.readChunk(channelHandlerContext == null ? (ByteBufAllocator)null : channelHandlerContext.alloc());
  }

  /**
   * Returns a {@link ByteBuf} representing a chunk of this {@link
   * ByteBufChunkedInput}'s overall input as represented by the {@link
   * ByteBuf} supplied to it {@linkplain #ByteBufChunkedInput(ByteBuf)
   * at construction time}.
   *
   * <p>This method may, and often does, return {@code null}.</p>
   *
   * <p>The {@link ByteBuf} that is returned by this implementation
   * is, if non-{@code null}, a {@linkplain
   * ByteBuf#readRetainedSlice(int) retained slice} of the {@link
   * ByteBuf} supplied to this {@link ByteBufChunkedInput} {@linkplain
   * #ByteBufChunkedInput(ByteBuf, long) at construction time} whose
   * size is given by the return value of the {@link
   * #getChunkSize(ByteBuf)} method.</p>
   *
   * @param ignoredByteBufAllocator a {@link ByteBufAllocator} that
   * this implementation ignores; may be {@code null}
   *
   * @return a {@link ByteBuf} representing a chunk of this {@link
   * ByteBufChunkedInput}'s overall input as represented by the {@link
   * ByteBuf} supplied to it {@linkplain #ByteBufChunkedInput(ByteBuf)
   * at construction time}, or {@code null}
   *
   * @see #getChunkSize(ByteBuf)
   */
  @Override
  public final ByteBuf readChunk(final ByteBufAllocator ignoredByteBufAllocator) {
    return this.byteBuf.refCnt() <= 0 || (this.closed && !this.byteBuf.isReadable()) ? null : this.byteBuf.readRetainedSlice(Math.min(this.getChunkSize(this.byteBuf), this.byteBuf.readableBytes()));
  }

  /**
   * Returns the size of the chunk, in bytes, that will be returned by
   * the {@link #readChunk(ByteBufAllocator)} method.
   *
   * <p>This implementation returns the result of invoking {@code
   * source.}{@link ByteBuf#readableBytes() readableBytes()}.</p>
   *
   * @param source the {@link ByteBuf} that was {@linkplain
   * #ByteBufChunkedInput(ByteBuf) supplied at construction time};
   * will not be {@code null}
   *
   * @return the size of the chunk, in bytes, that will be returned by
   * the {@link #readChunk(ByteBufAllocator)} method; <strong>behavior
   * is undefined if this value is less than or equal to {@code
   * 0}</strong>
   *
   * @see #readChunk(ByteBufAllocator)
   */
  protected int getChunkSize(final ByteBuf source) {
    return source.readableBytes();
  }

  /**
   * Returns the length of this {@link ChunkedInput} implementation in bytes,
   * or {@code -1L} if a representation of its length is not
   * available.
   *
   * @return the length of this {@link ChunkedInput} implementation in
   * bytes, or {@code -1L}
   */
  @Override
  public final long length() {
    return this.lengthToReport;
  }

  /**
   * Returns the number of bytes read from this input.
   *
   * @return the number of bytes read from this input
   */
  @Override
  public final long progress() {
    // e.g. we've read <progress> of <length> bytes.  Other
    // ChunkedInput implementations return a valid number here even
    // when length() returns -1, so we do too.
    return this.byteBuf.readerIndex();
  }

  /**
   * Marks this {@link ByteBufChunkedInput} as being closed.
   *
   * <p>As a result the {@link #readChunk(ByteBufAllocator)} method
   * may return {@code null} in the future.</p>
   *
   * @see #readChunk(ByteBufAllocator)
   */
  @Override
  public final void close() {
    this.closed = true;
  }

}
