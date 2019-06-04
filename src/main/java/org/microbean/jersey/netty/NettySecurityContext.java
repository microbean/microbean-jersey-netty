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

  private final ChannelHandlerContext channelHandlerContext;
  
  public NettySecurityContext(final ChannelHandlerContext channelHandlerContext) {
    super();
    this.channelHandlerContext = Objects.requireNonNull(channelHandlerContext);
  }
  
  public ChannelHandlerContext getChannelHandlerContext() {
    return this.channelHandlerContext;
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
  
  @Override
  public String getAuthenticationScheme() {
    return null;
  }

};
