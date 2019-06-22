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

import java.net.URI; // for javadoc only

import java.security.Principal;

import javax.ws.rs.core.SecurityContext;

import org.glassfish.jersey.server.ApplicationHandler; // for javadoc only

/**
 * A fallback {@link SecurityContext} implementation that {@linkplain
 * #getUserPrincipal() provides no user <code>Principal</code>},
 * {@linkplain #isUserInRole(String) has no role or user database}, is
 * {@linkplain #isSecure() not secure}, and that {@linkplain
 * #getAuthenticationScheme() does not require authentication}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see JerseyChannelInboundHandler#JerseyChannelInboundHandler(URI, ApplicationHandler)
 */
public class SecurityContextAdapter implements SecurityContext {


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link SecurityContextAdapter}.
   */
  protected SecurityContextAdapter() {
    super();
  }


  /*
   * Instance methods.
   */
  

  /**
   * Returns {@code false} in all cases.
   *
   * @param role ignored by this implementation; may be {@code null}
   *
   * @return {@code false} in all cases
   *
   * @see SecurityContext#isUserInRole(String)
   */
  @Override
  public boolean isUserInRole(final String role) {
    return false;
  }

  /**
   * Returns {@code false} in all cases.
   *
   * @return {@code false} in all cases
   *
   * @see SecurityContext#isSecure()
   */
  @Override
  public boolean isSecure() {
    return false;
  }

  /**
   * Returns {@code null} when invoked.
   *
   * @return {@code null} when invoked
   *
   * @see SecurityContext#getUserPrincipal()
   */
  @Override
  public Principal getUserPrincipal() {
    return null;
  }

  /**
   * Returns {@code null} when invoked.
   *
   * @return {@code null} when invoked
   *
   * @see SecurityContext#getAuthenticationScheme()
   */
  @Override
  public String getAuthenticationScheme() {
    return null;
  }

};
