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

import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.stream.ChunkedInput;

/**
 * A {@link ChunkedInput} implementation that uses a {@link Function}
 * to read from a {@link ByteBuf} and return a relevant chunk of its
 * data.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #readChunk(ByteBufAllocator)
 */
public class FunctionalByteBufChunkedInput<T> implements ChunkedInput<T> {


  /*
   * Instance fields.
   */


  /**
   * The {@link ByteBuf} from which to read.
   *
   * <p>This field is never {@code null}.</p>
   */
  private final ByteBuf byteBuf;

  /**
   * A {@link Function} that will be supplied with
   * a {@link ByteBuf} whose {@linkplain ByteBuf#readableBytes()
   * readable bytes} will be used to synthesize a chunk that will be
   * returned by the {@link #readChunk(ByteBufAllocator)} method.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #FunctionalByteBufChunkedInput(ByteBuf, Function, long)
   */
  private final Function<? super ByteBuf, ? extends T> chunkReader;

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
   * Creates a new {@link FunctionalByteBufChunkedInput} whose {@link #length()}
   * method will return {@code -1L}.
   *
   * @param byteBuf the {@link ByteBuf} from which chunks will be
   * {@linkplain #readChunk(ByteBufAllocator) read}; must not be
   * {@code null}
   *
   * @param chunkReader a {@link Function} that will be supplied with
   * a {@link ByteBuf} whose {@linkplain ByteBuf#readableBytes()
   * readable bytes} will be used to synthesize a chunk that will be
   * returned by the {@link #readChunk(ByteBufAllocator)} method; must
   * not be {@code null}
   *
   * @see #FunctionalByteBufChunkedInput(ByteBuf, Function, long)
   */
  public FunctionalByteBufChunkedInput(final ByteBuf byteBuf,
                                       final Function<? super ByteBuf, ? extends T> chunkReader) {
    this(byteBuf, chunkReader, -1L);
  }

  /**
   * Creates a new {@link FunctionalByteBufChunkedInput}.
   *
   * @param byteBuf the {@link ByteBuf} from which chunks will be
   * {@linkplain #readChunk(ByteBufAllocator) read}; must not be
   * {@code null}
   *
   * @param chunkReader a {@link Function} that will be supplied with
   * a {@link ByteBuf} whose {@linkplain ByteBuf#readableBytes()
   * readable bytes} will be used to synthesize a chunk that will be
   * returned by the {@link #readChunk(ByteBufAllocator)} method; must
   * not be {@code null}
   *
   * @param lengthToReport the length of this {@link ChunkedInput}
   * implementation in bytes to be reported by the {@link #length()}
   * method; may be {@code -1L} (and very often is) if a
   * representation of its length is not available.
   *
   * @exception NullPointerException if either {@code byteBuf} or
   * {@code chunkReader} is {@code null}
   */
  public FunctionalByteBufChunkedInput(final ByteBuf byteBuf,
                                       final Function<? super ByteBuf, ? extends T> chunkReader,
                                       final long lengthToReport) {
    super();
    this.byteBuf = Objects.requireNonNull(byteBuf);
    this.chunkReader = Objects.requireNonNull(chunkReader);
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
   * @return a chunk of the overall data, or {@code null}
   *
   * @see #readChunk(ByteBufAllocator)
   *
   * @deprecated Please use the {@link #readChunk(ByteBufAllocator)}
   * method instead.
   */
  @Deprecated
  @Override
  public final T readChunk(final ChannelHandlerContext channelHandlerContext) {
    return this.readChunk(channelHandlerContext == null ? (ByteBufAllocator)null : channelHandlerContext.alloc());
  }

  /**
   * Returns a chunk of this {@link FunctionalByteBufChunkedInput}'s
   * overall input as represented by the {@link ByteBuf} and using the
   * {@link Function} supplied to it {@linkplain
   * #FunctionalByteBufChunkedInput(ByteBuf, Function, long) at
   * construction time}.
   *
   * <p>This method may, and often does, return {@code null}.</p>
   *
   * <p>The chunk that is returned by this method will be, if
   * non-{@code null}, synthesized from a {@linkplain
   * ByteBuf#readRetainedSlice(int) retained slice} of the {@link
   * ByteBuf} supplied to this {@link ByteBufChunkedInput} {@linkplain
   * #FunctionalByteBufChunkedInput(ByteBuf, Function, long) at
   * construction time} whose size is given by the return value of the
   * {@link #getChunkSize(ByteBuf)} method.</p>
   *
   * @param ignoredByteBufAllocator a {@link ByteBufAllocator} that an
   * implementation may use if it wishes but normally should have no
   * use for; may be {@code null}
   *
   * @return a chunk of this {@link ByteBufChunkedInput}'s overall
   * input as represented by the {@link ByteBuf} supplied to it
   * {@linkplain #FunctionalByteBufChunkedInput(ByteBuf, Function,
   * long) at construction time}, or {@code null}
   *
   * @see #getChunkSize(ByteBuf)
   *
   * @see #FunctionalByteBufChunkedInput(ByteBuf, Function, long)
   */
  @Override
  public final T readChunk(final ByteBufAllocator ignoredByteBufAllocator) {
    return this.byteBuf.refCnt() <= 0 || (this.closed && !this.byteBuf.isReadable()) ? null : this.chunkReader.apply(this.byteBuf.readRetainedSlice(Math.min(this.getChunkSize(this.byteBuf), this.byteBuf.readableBytes())).asReadOnly());
  }

  /**
   * Returns the size of the chunk, in bytes, that will be read and
   * synthesized into the return value the {@link
   * #readChunk(ByteBufAllocator)} method.
   *
   * <p>This implementation returns the result of invoking {@code
   * source.}{@link ByteBuf#readableBytes() readableBytes()} on the
   * supplied {@code source}.</p>
   *
   * @param source the {@link ByteBuf} that was {@linkplain
   * #FunctionalByteBufChunkedInput(ByteBuf, Function, long) supplied
   * at construction time}; will not be {@code null}
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
   * Marks this {@link FunctionalByteBufChunkedInput} as being closed.
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
