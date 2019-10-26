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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import io.netty.channel.ChannelOutboundInvoker;

/**
 * An {@link AbstractChannelOutboundInvokingOutputStream} that
 * {@linkplain #createMessage(ByteBuf) creates its messages} from
 * {@link ByteBuf} instances.
 *
 * @param <T> the type of message that will be written
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #createMessage(ByteBuf)
 */
public abstract class AbstractByteBufBackedChannelOutboundInvokingOutputStream<T> extends AbstractChannelOutboundInvokingOutputStream<T> {


  /*
   * Instance fields.
   */


  private final ByteBufCreator byteBufCreator;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}.
   *
   * @param channelOutboundInvoker the {@link ChannelOutboundInvoker}
   * to which operations are adapted; must not be {@code null}
   *
   * @param closeChannelOutboundInvoker whether {@link
   * ChannelOutboundInvoker#close(ChannelPromise)} will be called on
   * the supplied {@link ChannelOutboundInvoker} when {@link #close()
   * close()} is called
   *
   * @see
   * #AbstractByteBufBackedChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean, ByteBufCreator)
   */
  protected AbstractByteBufBackedChannelOutboundInvokingOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                             final boolean closeChannelOutboundInvoker) {
    this(channelOutboundInvoker, Integer.MAX_VALUE, closeChannelOutboundInvoker, null);
  }

  /**
   * Creates a new {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}.
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
   * @see
   * #AbstractByteBufBackedChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean, ByteBufCreator)
   */
  protected AbstractByteBufBackedChannelOutboundInvokingOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                             final int flushThreshold,
                                                             final boolean closeChannelOutboundInvoker) {
    this(channelOutboundInvoker, flushThreshold, closeChannelOutboundInvoker, null);
  }

  /**
   * Creates a new {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}.
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
   * @param byteBufCreator a {@link ByteBufCreator} that will be used
   * to {@linkplain ByteBufCreator#toByteBuf(byte[], int, int) create
   * <code>ByteBuf</code> instances}; may be {@code null} in which
   * case a default {@link ByteBufCreator} adapting {@link
   * Unpooled#wrappedBuffer(byte[], int, int)} will be used instead
   *
   * @see ByteBufCreator
   *
   * @see Unpooled#wrappedBuffer(byte[], int, int)
   */
  protected AbstractByteBufBackedChannelOutboundInvokingOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                             final int flushThreshold,
                                                             final boolean closeChannelOutboundInvoker,
                                                             final ByteBufCreator byteBufCreator) {
    super(channelOutboundInvoker, flushThreshold, closeChannelOutboundInvoker);
    if (byteBufCreator == null) {
      this.byteBufCreator = (bytes, offset, length) -> Unpooled.wrappedBuffer(bytes, offset, length);
    } else {
      this.byteBufCreator = byteBufCreator;
    }
  }


  /*
   * Instance methods.
   */


  /**
   * Returns the result of invoking the {@link
   * #createMessage(ByteBuf)} method with a {@link ByteBuf} returned
   * by the {@link ByteBufCreator} {@linkplain
   * #AbstractByteBufBackedChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean, ByteBufCreator) supplied at construction time}.
   *
   * @param bytes {@inheritDoc}
   *
   * @param offset {@inheritDoc}
   *
   * @param length {@inheritDoc}
   *
   * @return {@inheritDoc}
   *
   * @exception IOException if the {@link #createMessage(ByteBuf)}
   * method throws an {@link IOException}
   *
   * @see #createMessage(ByteBuf)
   */
  @Override
  protected final T createMessage(final byte[] bytes, final int offset, final int length) throws IOException {
    return this.createMessage(this.byteBufCreator.toByteBuf(bytes, offset, length));
  }

  /**
   * Creates and returns a new message to be {@linkplain
   * ChannelOutboundInvoker#write(Object, ChannelPromise) written}.
   *
   * <p>This method is called by the {@link #createMessage(byte[],
   * int, int)} method.</p>
   *
   * @param content the {@link ByteBuf} to construct the message from;
   * will never be {@code null}; must be read in its entirety,
   * i.e. the return value of its {@link ByteBuf#readableBytes()}
   * method after this method has completed must be {@code 0}
   *
   * @return a new message
   *
   * @exception IOException if an error occurs
   *
   * @see #createMessage(byte[], int, int)
   */
  protected abstract T createMessage(final ByteBuf content) throws IOException;


  /*
   * Inner and nested classes.
   */


  /**
   * An allocator of {@link ByteBuf}s that uses a {@code byte} array
   * or a portion of a {@code byte} array as its raw materials.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see #toByteBuf(byte[], int, int)
   */
  @FunctionalInterface
  public static interface ByteBufCreator {

    /**
     * Returns a {@link ByteBuf} that uses the designated {@code byte}
     * array portion as its raw materials.
     *
     * <p>Implementations of this method must not return {@code
     * null}.</p>
     *
     * @param bytes the {@code byte} array from which to read; must
     * not be {@code null}
     *
     * @param offset the zero-based offset of the supplied {@code
     * byte} array at which to start reading; must be {@code 0} or a
     * positive {@code int} that is less than the length of the
     * supplied {@code byte} array
     *
     * @param length the number of bytes to read; must be {@code 0} or
     * a {@code positive int} that is less than or equal to the length
     * of the supplied {@code byte} array minus the supplied {@code
     * offset}
     *
     * @return a non-{@code null} {@link ByteBuf}
     *
     * @see Unpooled#wrappedBuffer(byte[], int, int)
     */
    public ByteBuf toByteBuf(final byte[] bytes, final int offset, final int length);

  }

}
