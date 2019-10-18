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

import io.netty.buffer.ByteBufAllocator; // for javadoc only

import io.netty.handler.stream.ChunkedInput;

/**
 * A {@link ChunkedInput} whose {@linkplain #setEndOfInput() end of
 * input condition may be explicitly set}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #setEndOfInput()
 */
public interface BoundedChunkedInput<T> extends ChunkedInput<T> {

  /**
   * Indicates irrevocably that there will be no more input supplied
   * to this {@link ChunkedInput}, and that therefore at some point
   * its {@link #readChunk(ByteBufAllocator)} method will return
   * {@code null}, and its {@link #isEndOfInput()} method will return
   * {@code true}.
   *
   * @see #isEndOfInput()
   *
   * @see #readChunk(ByteBufAllocator)
   *
   * @see #close()
   *
   * @see ChunkedInput
   */
  public void setEndOfInput();
  
}
