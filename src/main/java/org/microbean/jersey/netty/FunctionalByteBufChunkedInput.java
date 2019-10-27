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

import java.util.logging.Level;
import java.util.logging.Logger;

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
 *
 * @deprecated Slated for removal.
 */
@Deprecated
public class FunctionalByteBufChunkedInput<T> implements BoundedChunkedInput<T> {


  /*
   * Static fields.
   */


  private static final String cn = FunctionalByteBufChunkedInput.class.getName();

  private static final Logger logger = Logger.getLogger(cn);
  

  /*
   * Instance fields.
   */


  /**
   * The {@link ByteBuf} from which to read.
   *
   * <p>This field may be {@code null}.</p>
   */
  private volatile ByteBuf byteBuf;

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

  private volatile boolean noMoreInput;
  
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
    final String mn = "isEndOfInput";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn);
    }

    // (Don't check this.closed and throw an IllegalStateException
    // because close() calls this method after setting this.closed to
    // true.  That ordering is important to prevent
    // setByteBuf(ByteBuf) from interfering during all this.)
    
    final boolean returnValue;
    if (this.noMoreInput) {
      ByteBuf byteBuf = this.byteBuf;
      if (byteBuf == null) {
        returnValue = true;
      } else if (byteBuf.refCnt() <= 0) {
        if (logger.isLoggable(Level.WARNING)) {
          logger.logp(Level.WARNING, cn, mn, "Unexpected refCnt: {0}", byteBuf.refCnt());
        }
        returnValue = true;
      } else {
        returnValue = !byteBuf.isReadable();
        // Ensure that nothing more CAN be written to the byteBuf.
        byteBuf = byteBuf.asReadOnly();
        assert byteBuf != null;
        assert byteBuf.isReadOnly();
        this.byteBuf = byteBuf;
      }
    } else {
      returnValue = false;
    }

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn, Boolean.valueOf(returnValue));
    }
    return returnValue;
  }

  /**
   * Irrevocably closes this {@link FunctionalByteBufChunkedInput} to
   * new input.
   */
  @Override
  public final void setEndOfInput() {
    final String mn = "setEndOfInput";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn);
    }
    this.noMoreInput = true;
    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn);
    }
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
   * ByteBuf} {@linkplain #FunctionalByteBufChunkedInput(ByteBuf,
   * Function, long) supplied at construction time} whose size is
   * given by the return value of the {@link #getChunkSize(ByteBuf)}
   * method.</p>
   *
   * @param ignoredByteBufAllocator a {@link ByteBufAllocator} that an
   * implementation may use if it wishes but normally should have no
   * use for; may be {@code null}
   *
   * @return a chunk of this {@link FunctionalByteBufChunkedInput}'s
   * overall input, or {@code null}
   *
   * @exception IllegalStateException if {@link #close()} has been
   * called
   *
   * @see #getChunkSize(ByteBuf)
   *
   * @see #FunctionalByteBufChunkedInput(ByteBuf, Function, long)
   *
   * @see #isEndOfInput()
   *
   * @see #setEndOfInput()
   *
   * @see #close()
   */
  @Override
  public final T readChunk(final ByteBufAllocator ignoredByteBufAllocator) {
    final String mn = "readChunk";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn, ignoredByteBufAllocator);
    }
    if (this.closed) {
      throw new IllegalStateException("closed");
    }

    final T returnValue;
    final ByteBuf byteBuf = this.byteBuf;
    if (byteBuf == null) {
      returnValue = null;
    } else if (byteBuf.refCnt() <= 0) {
      if (logger.isLoggable(Level.WARNING)) {
        logger.logp(Level.WARNING, cn, mn, "Unexpected refCnt: {0}", byteBuf.refCnt());
      }
      returnValue = null;
    } else if (!byteBuf.isReadable()) {
      returnValue = null;
    } else {
      final int readableBytes = byteBuf.readableBytes();
      assert readableBytes > 0; // ...because we already checked isReadable()
      final int numberOfBytesToRead;
      final int chunkSize = this.getChunkSize(byteBuf);
      if (readableBytes < chunkSize) {
        numberOfBytesToRead = readableBytes;
      } else {
        numberOfBytesToRead = chunkSize;
      }
      // TODO: look REALLY hard at whether this slice should be retained or not.
      final ByteBuf retainedSlice = byteBuf.readRetainedSlice(numberOfBytesToRead);
      assert retainedSlice != null;
      returnValue = this.chunkReader.apply(retainedSlice.asReadOnly());
    }

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn, returnValue);
    }
    return returnValue;
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
   * at construction time}; may be {@code null} in which case {@code
   * 0} will be returned
   *
   * @return the size of the chunk, in bytes, that will be returned by
   * the {@link #readChunk(ByteBufAllocator)} method; <strong>behavior
   * is undefined if this value is less than or equal to {@code
   * 0}</strong>
   *
   * @exception NullPointerException if for any reason {@code source}
   * is {@code null}
   *
   * @see #readChunk(ByteBufAllocator)
   */
  protected int getChunkSize(final ByteBuf source) {
    return source == null ? 0 : source.readableBytes();
  }

  /**
   * Returns the length of this {@link ChunkedInput} implementation in bytes,
   * or {@code -1L} if a representation of its length is not
   * available.
   *
   * @return the length of this {@link ChunkedInput} implementation in
   * bytes, or {@code -1L}
   *
   * @exception IllegalStateException if {@link #close()} has been
   * called
   */
  @Override
  public final long length() {
    if (this.closed) {
      throw new IllegalStateException("closed");
    }
    return this.lengthToReport;
  }

  /**
   * Returns the number of bytes read from this input.
   *
   * <p>If the return value of an invocation of the {@link #length()}
   * method returns zero or a positive integer, then the value
   * returned by an invocation of this method will be less than or
   * equal to that value.</p>
   *
   * @return the number of bytes read from this input
   *
   * @exception IllegalStateException if {@link #close()} has been
   * called
   *
   * @see #length()
   */
  @Override
  public final long progress() {
    if (this.closed) {
      throw new IllegalStateException("closed");
    }
    // e.g. we've read <progress> of <length> bytes.  Other
    // ChunkedInput implementations return a valid number here even
    // when length() returns -1L, so we do too.
    final long returnValue;
    final ByteBuf byteBuf = this.byteBuf;
    if (byteBuf == null) {
      returnValue = 0L;
    } else if (byteBuf.refCnt() <= 0) {
      if (logger.isLoggable(Level.WARNING)) {
        logger.logp(Level.WARNING, cn, "progress", "Unexpected refCnt: {0}", byteBuf.refCnt());
      }
      returnValue = 0L;
    } else {
      returnValue = byteBuf.readerIndex();
    }
    return returnValue;
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
    final String mn = "close";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn);
    }
    if (this.closed) {
      throw new IllegalStateException("closed");
    }
    
    this.closed = true;
    this.setEndOfInput();
    final ByteBuf byteBuf = this.byteBuf;
    if (byteBuf != null) {
      if (byteBuf.refCnt() <= 0) {
        if (logger.isLoggable(Level.WARNING)) {
          logger.logp(Level.WARNING, cn, mn, "Unexpected refCnt: {0}", byteBuf.refCnt());
        }
      } else {
        assert byteBuf.isReadOnly();
        byteBuf.release();
      }
    }
    this.byteBuf = null;

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn);
    }
  }

}
