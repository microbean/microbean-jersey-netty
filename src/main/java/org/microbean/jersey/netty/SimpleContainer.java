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

import javax.ws.rs.core.Application;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ResourceConfig;

import org.glassfish.jersey.server.spi.Container;

public class SimpleContainer implements Container {

  private volatile ApplicationHandler applicationHandler;

  public SimpleContainer(final Application application) {
    this(new ApplicationHandler(Objects.requireNonNull(application)));
  }

  public SimpleContainer(final ApplicationHandler applicationHandler) {
    super();
    this.applicationHandler = Objects.requireNonNull(applicationHandler);
  }

  @Override
  public final ResourceConfig getConfiguration() {
    final ResourceConfig returnValue;
    final ApplicationHandler handler = this.getApplicationHandler();
    if (handler == null) {
      returnValue = null;
    } else {
      returnValue = handler.getConfiguration();
    }
    return returnValue;
  }

  @Override
  public final ApplicationHandler getApplicationHandler() {
    return this.applicationHandler;
  }

  @Override
  public final void reload() {
    this.reload(this.getConfiguration());
  }

  @Override
  public void reload(final ResourceConfig resourceConfig) {
    ApplicationHandler handler = this.getApplicationHandler();
    if (handler != null) {
      handler.onShutdown(this);
    }
    handler = new ApplicationHandler(resourceConfig);
    this.applicationHandler = handler;
    handler.onReload(this);
    handler.onStartup(this);
  }

}
