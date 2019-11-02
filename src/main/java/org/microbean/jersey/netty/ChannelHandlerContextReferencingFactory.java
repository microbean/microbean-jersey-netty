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

import javax.inject.Inject;
import javax.inject.Provider;

import javax.ws.rs.core.GenericType;

import io.netty.channel.ChannelHandlerContext;

import org.glassfish.jersey.internal.inject.ReferencingFactory;

import org.glassfish.jersey.internal.util.collection.Ref;

final class ChannelHandlerContextReferencingFactory extends ReferencingFactory<ChannelHandlerContext> {

  static final GenericType<Ref<ChannelHandlerContext>> genericRefType = new GenericType<Ref<ChannelHandlerContext>>() {};
  
  @Inject
  private ChannelHandlerContextReferencingFactory(final Provider<Ref<ChannelHandlerContext>> provider) {
    super(provider);
  }
  
}
