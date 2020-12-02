/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2020 microBean™.
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

import java.util.function.Supplier;

/**
 * A {@link Supplier} that returns the {@link Object} supplied at
 * construction time.
 *
 * @param <T> the type of object to supply
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #get()
 */
final class ImmutableSupplier<T> implements Supplier<T> {

  private final T object;

  /**
   * Creates a new {@link ImmutableSupplier}.
   *
   * @param object the object to supply; may be {@code null}
   *
   * @see #get()
   */
  public ImmutableSupplier(final T object) {
    super();
    this.object = object;
  }

  /**
   * Returns the object supplied at construction time.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @return the object supplied at construction time which may be
   * {@code null}
   *
   * @see #ImmutableSupplier(Object)
   */
  @Override
  public final T get() {
    return this.object;
  }

}
