/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019–2021 microBean™.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.channel.ChannelPromise;

/**
 * An {@link OutputStream} that delegates writing and flushing
 * operations to a {@link ChannelOutboundInvoker}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances of this class are safe for concurrent use by multiple
 * threads.</p>
 *
 * @param <T> the type of message that will be written; see {@link
 * #createMessage(byte[], int, int)}
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see ChannelOutboundInvoker
 */
public abstract class AbstractChannelOutboundInvokingOutputStream<T> extends OutputStream {


  /*
   * Instance fields.
   */


  /**
   * The {@link ChannelOutboundInvoker} underlying this {@link
   * AbstractChannelOutboundInvokingOutputStream} implementation to
   * which most operations are adapted.
   *
   * @see ChannelOutboundInvoker
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   */
  protected final ChannelOutboundInvoker channelOutboundInvoker;

  /**
   * Indicates whether the {@link #channelOutboundInvoker
   * ChannelOutboundInvoker} should also be {@linkplain
   * ChannelOutboundInvoker#close(ChannelPromise) closed} when {@link
   * #close() close()} is called.
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   */
  protected final boolean closeChannelOutboundInvoker;

  private final int flushThreshold;

  private volatile int bytesWritten;


  /*
   * Constructors;
   */


  /**
   * Creates a new {@link AbstractChannelOutboundInvokingOutputStream}
   * that does not automatically flush and that does not ever
   * {@linkplain ChannelOutboundInvoker#close() close} the supplied
   * {@link ChannelOutboundInvoker}.
   *
   * @param channelOutboundInvoker the {@link ChannelOutboundInvoker}
   * to which operations are adapted; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelOutboundInvoker}
   * is {@code null}
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   *
   * @see ChannelOutboundInvoker
   */
  protected AbstractChannelOutboundInvokingOutputStream(final ChannelOutboundInvoker channelOutboundInvoker) {
    this(channelOutboundInvoker, Integer.MAX_VALUE, false);
  }

  /**
   * Creates a new {@link AbstractChannelOutboundInvokingOutputStream}
   * that does not automatically flush.
   *
   * @param channelOutboundInvoker the {@link ChannelOutboundInvoker}
   * to which operations are adapted; must not be {@code null}
   *
   * @param closeChannelOutboundInvoker whether {@link
   * ChannelOutboundInvoker#close(ChannelPromise)} will be called on
   * the supplied {@link ChannelOutboundInvoker} when {@link #close()
   * close()} is called
   *
   * @exception NullPointerException if {@code channelOutboundInvoker}
   * is {@code null}
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   *
   * @see ChannelOutboundInvoker
   *
   * @see #close()
   */
  protected AbstractChannelOutboundInvokingOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                        final boolean closeChannelOutboundInvoker) {
    this(channelOutboundInvoker, Integer.MAX_VALUE, closeChannelOutboundInvoker);
  }

  /**
   * Creates a new {@link
   * AbstractChannelOutboundInvokingOutputStream}.
   *
   * @param channelOutboundInvoker the {@link ChannelOutboundInvoker}
   * to which operations are adapted; must not be {@code null}
   *
   * @param flushThreshold the minimum number of bytes that this
   * instance has to {@linkplain #write(byte[], int, int) write}
   * before an automatic {@linkplain #flush() flush} will take place;
   * if less than {@code 0} {@code 0} will be used instead; if {@code
   * Integer#MAX_VALUE} then no automatic flushing will occur
   *
   * @param closeChannelOutboundInvoker whether {@link
   * ChannelOutboundInvoker#close(ChannelPromise)} will be called on
   * the supplied {@link ChannelOutboundInvoker} when {@link #close()
   * close()} is called
   *
   * @exception NullPointerException if {@code channelOutboundInvoker}
   * is {@code null}
   *
   * @see ChannelOutboundInvoker
   *
   * @see #getFlushThreshold()
   *
   * @see #close()
   */
  protected AbstractChannelOutboundInvokingOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                        final int flushThreshold,
                                                        final boolean closeChannelOutboundInvoker) {
    super();
    this.flushThreshold = Math.max(0, flushThreshold);
    this.channelOutboundInvoker = Objects.requireNonNull(channelOutboundInvoker);
    this.closeChannelOutboundInvoker = closeChannelOutboundInvoker;
  }


  /*
   * Instance methods.
   */


  /**
   * Returns the minimum number of bytes that this {@link
   * AbstractChannelOutboundInvokingOutputStream} implementation has
   * to {@linkplain #write(byte[], int, int) write} before an
   * automatic {@linkplain #flush() flush} will take place.
   *
   * <p>This method will always return {@code 0} or a positive {@code
   * int}.</p>
   *
   * <p>If this method returns {@code 0}, then a call to {@link
   * #flush()} will be made at some point after every {@link
   * #write(byte[], int, int)} invocation.</p>
   *
   * <p>If this method returns {@link Integer#MAX_VALUE}, then no
   * automatic flushing will occur.</p>
   *
   * @return the minimum number of bytes that this {@link
   * AbstractChannelOutboundInvokingOutputStream} implementation has
   * to {@linkplain #write(byte[], int, int) write} before an
   * automatic {@linkplain #flush() flush} will take place; always
   * {@code 0} or a positive {@code int}
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   *
   * @see ChannelOutboundInvoker#flush()
   */
  public final int getFlushThreshold() {
    return this.flushThreshold;
  }

  @Override
  public final void write(final int singleByte) throws IOException {
    this.write(this.createMessage(singleByte), 1);
  }

  @Override
  public final void write(final byte[] bytes) throws IOException {
    this.write(this.createMessage(bytes), bytes.length);
  }

  @Override
  public final void write(final byte[] bytes, final int offset, final int length) throws IOException {
    if (offset < 0 || length < 0 || offset + length > bytes.length) {
      throw new IndexOutOfBoundsException();
    }
    this.write(this.createMessage(bytes, offset, length), length);
  }

  private final void write(final T message, final int length) throws IOException {
    final ChannelPromise channelPromise = this.newPromise();
    final int flushThreshold = this.getFlushThreshold();
    switch (flushThreshold) {
    case 0:
      // Flush previous writes, if any
      this.channelOutboundInvoker.flush();
      break;
    case Integer.MAX_VALUE:
      break;
    default:
      final int bytesWritten = this.bytesWritten; // volatile read
      if (bytesWritten > flushThreshold) {
        // Flush previous writes, if any, and set our "days since
        // flush" back to 0 (see #flush())
        this.flush();
      } else if (channelPromise.isVoid()) {
        // Optimistically assume the write will succeed; if we get
        // this wrong, all that happens is maybe we don't flush as
        // often as expected.
        this.bytesWritten = bytesWritten + length;
      } else {
        channelPromise.addListener(f -> this.bytesWritten = bytesWritten + length); // volatile write
      }
    }
    this.channelOutboundInvoker.write(message, channelPromise);
    maybeThrow(channelPromise.cause());
  }

  /**
   * Returns a new message representing the single supplied {@code
   * byte} to be {@linkplain ChannelOutboundInvoker#write(Object,
   * ChannelPromise) written} by this {@link
   * AbstractChannelOutboundInvokingOutputStream}'s various {@link
   * #write(byte[], int, int) write} methods.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p><strong>Note:</strong> The default implementation of this
   * method is inefficient: it creates a new {@code byte[]} holding
   * the sole {@code byte} represented by the {@code singleByte}
   * parameter value and calls {@link #createMessage(byte[], int,
   * int)} and returns its result.  Subclasses are encouraged, but not
   * required, to override this method to be more efficient.</p>
   *
   * <p>Overrides of this method should be stateless.</p>
   *
   * @param singleByte an {@code int} whose low-order bits hold a
   * {@code byte} to be written
   *
   * @return a new, non-{@code null} message to {@linkplain
   * ChannelOutboundInvoker#write(Object, ChannelPromise) write}
   *
   * @exception IOException if an error occurs
   *
   * @see #createMessage(byte[], int, int)
   *
   * @see #write(int)
   *
   * @see OutputStream#write(int)
   *
   * @see ChannelOutboundInvoker#write(Object, ChannelPromise)
   */
  protected T createMessage(final int singleByte) throws IOException {
    return this.createMessage(new byte[] { (byte)singleByte }, 0, 1);
  }

  /**
   * Returns a new message representing the supplied {@code byte}
   * array that will be {@linkplain
   * ChannelOutboundInvoker#write(Object, ChannelPromise) written} by
   * this {@link AbstractChannelOutboundInvokingOutputStream}'s
   * various {@link #write(byte[], int, int) write} methods.
   *
   * <p>This method does not and its overrides must not return {@code
   * null}.</p>
   *
   * <p>This method is and its overrides should be stateless.</p>
   *
   * <p>The default implementation of this method simply calls {@link
   * #createMessage(byte[], int, int)}.  Subclasses may wish to
   * override this method if a more efficient implementation is
   * possible.</p>
   *
   * @param bytes a {@code byte} array originating from,
   * <em>e.g.</em>, a {@link #write(byte[])} method invocation; will
   * never be {@code null}
   *
   * @return a new, non-{@code null} message to {@linkplain
   * ChannelOutboundInvoker#write(Object, ChannelPromise) write}
   *
   * @exception NullPointerException if {@code bytes} is {@code null}
   *
   * @exception IOException if an error occurs during the actual
   * creation of the message
   *
   * @see #createMessage(byte[], int, int)
   *
   * @see #write(byte[])
   *
   * @see OutputStream#write(byte[])
   *
   * @see ChannelOutboundInvoker#write(Object, ChannelPromise)
   */
  protected T createMessage(final byte[] bytes) throws IOException {
    return this.createMessage(bytes, 0, bytes.length);
  }

  /**
   * Returns a new message representing a portion (or all) of the
   * supplied {@code byte} array that will be {@linkplain
   * ChannelOutboundInvoker#write(Object, ChannelPromise) written} by
   * this {@link AbstractChannelOutboundInvokingOutputStream}'s
   * various {@link #write(byte[], int, int) write} methods.
   *
   * <p>Implementations of this method must not return {@code
   * null}.</p>
   *
   * <p>Implementations of this method should be stateless.</p>
   *
   * @param bytes a {@code byte} array originating from,
   * <em>e.g.</em>, a {@link #write(byte[], int, int)} method
   * invocation; will never be {@code null}
   *
   * @param offset the (validated) offset within the supplied {@code
   * byte} array from which to start reading; will always be {@code 0}
   * or a positive {@code int} less than {@code length}
   *
   * @param length the (validated) length of the portion to read; will
   * always be {@code 0} or a positive {@code int} less than or equal
   * to the {@code length} of the supplied {@code byte} array
   *
   * @return a new, non-{@code null} message to {@linkplain
   * ChannelOutboundInvoker#write(Object, ChannelPromise) write}
   *
   * @exception NullPointerException if {@code bytes} is {@code null}
   *
   * @exception IndexOutOfBoundsException if {@code offset} is
   * negative, or {@code length} is negative, or {@code offset +
   * length} is greater than the length of {@code bytes}
   *
   * @exception IOException if an error occurs during the actual
   * creation of the message
   *
   * @see #write(byte[], int, int)
   *
   * @see OutputStream#write(byte[], int, int)
   *
   * @see ChannelOutboundInvoker#write(Object, ChannelPromise)
   */
  protected abstract T createMessage(final byte[] bytes, final int offset, final int length) throws IOException;

  /**
   * Returns a new, possibly {@code null}, message that should be
   * written when {@link #close()} is invoked.
   *
   * <p>This method and its overrides may return {@code null} to
   * indicate that no such write is required.</p>
   *
   * <p>The default implementation of this method returns {@code
   * null}.</p>
   *
   * <p>Overrides of this method should be stateless.</p>
   *
   * @return a final message to write when {@link #close() close()} is
   * called, or {@code null} if no final message needs to be written
   *
   * @see #close()
   *
   * @exception IOException if an error occurs
   */
  protected T createLastMessage() throws IOException {
    return null;
  }

  /**
   * Creates and returns new {@link ChannelPromise}s that will be used
   * in many {@link ChannelOutboundInvoker} operations.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>The default implementation of this method returns the return
   * value of invoking {@link ChannelOutboundInvoker#newPromise()}.
   *
   * @return a new, non-{@code null} {@link ChannelPromise} that will
   * be supplied to many {@link ChannelOutboundInvoker} operations
   *
   * @see ChannelPromise
   *
   * @see ChannelOutboundInvoker#newPromise()
   *
   * @see ChannelOutboundInvoker#voidPromise()
   */
  protected ChannelPromise newPromise() {
    return this.channelOutboundInvoker.newPromise();
  }

  /**
   * Calls the {@link ChannelOutboundInvoker#flush()} method on the
   * {@link ChannelOutboundInvoker} {@linkplain
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean) supplied at construction time}.
   *
   * @see ChannelOutboundInvoker#flush()
   *
   * @see #getFlushThreshold()
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   */
  @Override
  public final void flush() {
    this.channelOutboundInvoker.flush();
    this.bytesWritten = 0; // volatile write
  }

  /**
   * {@linkplain OutputStream#close() Closes} this {@link
   * AbstractChannelOutboundInvokingOutputStream}, optionally
   * {@linkplain ChannelOutboundInvoker#writeAndFlush(Object,
   * ChannelPromise) writing and flushing} a {@linkplain
   * #createLastMessage() final message}, or simply just {@linkplain
   * #flush() flushing} first, before possibly {@linkplain
   * ChannelOutboundInvoker#close(ChannelPromise) closing the
   * underlying <code>ChannelOutboundInvoker</code>}.
   *
   * @exception IOException if the {@link #createLastMessage()} method
   * throws an {@link IOException}
   *
   * @see #createLastMessage()
   *
   * @see ChannelOutboundInvoker#close(ChannelPromise)
   *
   * @see
   * #AbstractChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean)
   */
  @Override
  public final void close() throws IOException {
    super.close();
    final Object lastMessage = this.createLastMessage();
    if (lastMessage == null) {
      this.flush();
    } else {
      final ChannelPromise channelPromise = this.newPromise();
      if (channelPromise.isVoid()) {
        this.channelOutboundInvoker.writeAndFlush(lastMessage, channelPromise);
        this.bytesWritten = 0; // volatile write
      } else {
        channelPromise.addListener(f -> this.bytesWritten = 0); // volatile write
        this.channelOutboundInvoker.writeAndFlush(lastMessage, channelPromise);
      }
      maybeThrow(channelPromise.cause());
    }
    if (this.closeChannelOutboundInvoker) {
      final ChannelPromise channelPromise = this.newPromise();
      this.channelOutboundInvoker.close(channelPromise);
      maybeThrow(channelPromise.cause());
    }
  }


  /*
   * Static methods.
   */


  private static final void maybeThrow(final Throwable cause) throws IOException {
    if (cause == null) {
      return;
    } else if (cause instanceof RuntimeException) {
      throw (RuntimeException)cause;
    } else if (cause instanceof IOException) {
      throw (IOException)cause;
    } else if (cause instanceof Exception) {
      throw new IOException(cause.getMessage(), cause);
    } else if (cause instanceof Error) {
      throw (Error)cause;
    } else {
      throw new InternalError();
    }
  }

}
