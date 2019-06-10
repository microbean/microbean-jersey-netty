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

import java.security.Principal;

import java.util.Objects;

import javax.ws.rs.core.SecurityContext;

import io.netty.channel.ChannelHandlerContext;

public class NettySecurityContext implements SecurityContext {

  public NettySecurityContext() {
    super();
  }
  
  @Override
  public boolean isUserInRole(final String role) {
    return false;
  }
  
  @Override
  public boolean isSecure() {
    return false;
  }
  
  @Override
  public Principal getUserPrincipal() {
    return null;
  }

  /**
   * Returns a {@link String} value representing the authentication
   * scheme used to protect a given resource, or {@code null} if the
   * given resource does not require authentication in order to be
   * accessed.</p>
   *
   * <p>This method may return {@code null}.</p>
   *
   * <p>Overrides of this method may return {@code null}.</p>
   *
   * <p>Values are the same as the <a
   * href="https://tools.ietf.org/html/rfc3875#section-4.1.1">CGI
   * variable {@code AUTH_TYPE}</a>.</p>
   *
   * @return a {@link String} value representing the authentication
   * scheme used to protect a given resource, or {@code null} if the
   * given resource does not require authentication in order to be
   * accessed
   */
  @Override
  public String getAuthenticationScheme() {
    return null;
  }

};
