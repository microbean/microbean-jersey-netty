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

/**
 * A functional interface whose implementations typically read from or
 * write to a given {@link ByteBuf}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #applyTo(ByteBuf)
 */
@FunctionalInterface
public interface ByteBufOperation {

  /**
   * Operates on the supplied {@link ByteBuf} in some way.
   *
   * @param target the {@link ByteBuf} to operate on; must not be
   * {@code null}
   *
   * @exception IOException if an error occurs
   */
  public void applyTo(final ByteBuf target) throws IOException;
  
}
