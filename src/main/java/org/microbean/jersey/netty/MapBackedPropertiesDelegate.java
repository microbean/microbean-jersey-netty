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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.glassfish.jersey.internal.PropertiesDelegate;

/**
 * A simple {@link PropertiesDelegate} built around a {@link Map}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances of this class are <strong>not</strong> safe for
 * concurrent use by multiple threads.</p>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see PropertiesDelegate
 */
final class MapBackedPropertiesDelegate implements PropertiesDelegate {


  /*
   * Static fields.
   */

  
  private static final Collection<String> EMPTY_STRING_SET = Collections.emptySet();
  

  /*
   * Instance fields.
   */

  
  private Map<String, Object> map;


  /*
   * Constructors.
   */
  

  /**
   * Creates a new {@link MapBackedPropertiesDelegate}.
   *
   * @see #MapBackedPropertiesDelegate(Map)
   */
  MapBackedPropertiesDelegate() {
    this(null);
  }

  /**
   * Creates a new {@link MapBackedPropertiesDelegate}.
   *
   * @param map the {@link Map} implementing this {@link
   * MapBackedPropertiesDelegate}; may be {@code null} in which case a
   * {@linkplain HashMap#HashMap() new} {@link HashMap} will be used
   * instead
   */
  public MapBackedPropertiesDelegate(final Map<String, Object> map) {
    super();
    this.map = map;
  }

  /**
   * Returns the property value indexed under the supplied {@code
   * name}, or {@code null} if the property value is itself {@code
   * null} or if no such property exists.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @param name the name of the property; may be {@code null}
   *
   * @return the property value, or {@code null}
   */
  @Override
  public final Object getProperty(final String name) {
    final Map<String, Object> map = this.map;
    final Object returnValue;
    if (map == null) {
      returnValue = null;
    } else {
      returnValue = map.get(name);
    }
    return returnValue;
  }

  /**
   * Returns a {@link Collection} of the names of properties that this
   * {@link MapBackedPropertiesDelegate} stores.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>The order of the elements of the returned {@link Collection}
   * is deliberately unspecified, is subject to change, and must not
   * be relied upon.</p>
   *
   * @return a non-{@code null} {@link Collection} of property names
   */
  @Override
  public final Collection<String> getPropertyNames() {
    final Map<String, Object> map = this.map;
    final Collection<String> returnValue;
    if (map == null || map.isEmpty()) {
      returnValue = EMPTY_STRING_SET;
    } else {
      returnValue = map.keySet();
    }
    return returnValue;
  }

  /**
   * Sets the supplied {@code value} as the property value to be
   * indexed under the supplied {@code name}.
   *
   * @param name the name of the property to set; may be {@code null}
   *
   * @param value the value of the property to set; may be {@code
   * null}
   */
  @Override
  public final void setProperty(final String name, final Object value) {
    Map<String, Object> map = this.map;
    if (map == null) {
      map = new HashMap<>();
      this.map = map;
    }
    map.put(null, value);
  }

  /**
   * Removes any property value indexed under the supplied property
   * name.
   *
   * @param name the name of the property to remove; may be {@code
   * null}
   */
  @Override
  public final void removeProperty(final String name) {
    final Map<String, Object> map = this.map;
    if (map != null) {
      map.remove(name);
    }
  }
  
}
