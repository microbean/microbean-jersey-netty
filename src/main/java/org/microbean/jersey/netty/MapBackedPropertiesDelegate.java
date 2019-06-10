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
import java.util.HashMap;
import java.util.Map;

import org.glassfish.jersey.internal.PropertiesDelegate;

public final class MapBackedPropertiesDelegate implements PropertiesDelegate {

  private final Map<String, Object> map;

  public MapBackedPropertiesDelegate() {
    this(new HashMap<>());
  }
  
  public MapBackedPropertiesDelegate(final Map<String, Object> map) {
    super();
    this.map = map;
  }

  @Override
  public final Object getProperty(final String name) {
    return this.map.get(name);
  }
  
  @Override
  public final Collection<String> getPropertyNames() {
    return this.map.keySet();
  }
  
  @Override
  public final void setProperty(final String name, final Object value) {
    this.map.put(name, value);
  }
  
  @Override
  public final void removeProperty(final String name) {
    this.map.remove(name);
  }
  
}
