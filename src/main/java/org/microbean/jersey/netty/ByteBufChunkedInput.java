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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator; // for javadoc only

/**
 * A {@link FunctionalByteBufChunkedInput} that reads from a {@link
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
 *
 * @see FunctionalByteBufChunkedInput
 */
public class ByteBufChunkedInput extends FunctionalByteBufChunkedInput<ByteBuf> {


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link ByteBufChunkedInput} whose {@link #length()}
   * method will return {@code -1L}.
   *
   * @param sourceByteBuf the {@link ByteBuf} from which chunks will be
   * {@linkplain #readChunk(ByteBufAllocator) read}; must not be
   * {@code null}
   *
   * @see #ByteBufChunkedInput(ByteBuf, long)
   */
  public ByteBufChunkedInput(final ByteBuf sourceByteBuf) {
    this(sourceByteBuf, -1L);
  }

  /**
   * Creates a new {@link ByteBufChunkedInput}.
   *
   * @param sourceByteBuf the {@link ByteBuf} from which chunks will
   * be {@linkplain #readChunk(ByteBufAllocator) read}; must not be
   * {@code null}
   *
   * @param lengthToReport the length of this {@link
   * ByteBufChunkedInput} in bytes to be reported by the {@link
   * #length()} method; may be {@code -1L} (and very often is) if a
   * representation of its length is not available.
   *
   * @exception NullPointerException if {@code sourceByteBuf} is
   * {@code null}
   */
  public ByteBufChunkedInput(final ByteBuf sourceByteBuf, final long lengthToReport) {
    super(sourceByteBuf, bb -> bb, lengthToReport);
  }

}
