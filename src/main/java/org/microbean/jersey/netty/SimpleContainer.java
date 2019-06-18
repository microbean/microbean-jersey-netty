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

import javax.ws.rs.core.Application;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ResourceConfig;

import org.glassfish.jersey.server.spi.Container;

/**
 * A straightforward {@link Container} implementation that is not
 * actually needed for Jersey integration.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see Container
 *
 * @see JerseyChannelInitializer
 */
public class SimpleContainer implements Container {


  /*
   * Instance fields.
   */

  
  private volatile ApplicationHandler applicationHandler;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link SimpleContainer}.
   *
   * @param application the {@link Application} to host; may be {@code
   * null} in which case a {@linkplain Application#Application() new}
   * {@link Application} will be used instead
   *
   * @see #SimpleContainer(ApplicationHandler)
   */
  public SimpleContainer(final Application application) {
    this(application == null ? (ApplicationHandler)null : new ApplicationHandler(application));
  }

  /**
   * Creates a new {@link SimpleContainer}.
   *
   * @param applicationHandler the {@link ApplicationHandler} that
   * actually does the work of hosting an application; may be {@code
   * null} in which case a {@linkplain
   * ApplicationHandler#ApplicationHandler() new} {@link
   * ApplicationHandler} will be used instead
   */
  public SimpleContainer(final ApplicationHandler applicationHandler) {
    super();
    this.applicationHandler = applicationHandler == null ? new ApplicationHandler() : applicationHandler;
  }

  /**
   * Returns a {@link ResourceConfig} representing the configuration
   * of the application hosted by this {@link SimpleContainer}.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @return a {@link ResourceConfig} representing the configuration
   * of the application hosted by this {@link SimpleContainer}, or
   * {@code null}
   *
   * @see ApplicationHandler#getConfiguration()
   */
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

  /**
   * Returns the {@link ApplicationHandler} this {@link
   * SimpleContainer} uses.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @return the {@link ApplicationHandler} this {@link
   * SimpleContainer} uses, or {@code null}
   */
  @Override
  public final ApplicationHandler getApplicationHandler() {
    return this.applicationHandler;
  }

  /**
   * Reloads the application hosted by this {@link SimpleContainer}.
   *
   * @see #reload(ResourceConfig)
   */
  @Override
  public final void reload() {
    this.reload(this.getConfiguration());
  }

  /**
   * Loads what amounts to a new application using the supplied {@link
   * ResourceConfig}.
   *
   * @param resourceConfig the {@link ResourceConfig} representing the
   * new application; may be {@code null}
   *
   * @see ApplicationHandler#onShutdown(Container)
   *
   * @see ApplicationHandler#onReload(Container)
   *
   * @see ApplicationHandler#onStartup(Container)
   */
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
