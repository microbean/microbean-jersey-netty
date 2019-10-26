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

import java.net.URI;

import java.util.Objects;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

import io.netty.handler.codec.http.HttpServerCodec;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import org.glassfish.jersey.server.ApplicationHandler;

public class NewJerseyChannelInitializer extends ChannelInitializer<Channel> {

  private final URI baseUri;

  private final ApplicationHandler applicationHandler;

  private static final EventExecutorGroup eventExecutorGroup = new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors()); // no idea how to size this
  
  public NewJerseyChannelInitializer(final URI baseUri,
                                     final ApplicationHandler applicationHandler) {
    super();
    this.baseUri = baseUri;
    this.applicationHandler = applicationHandler;
  }

  @Override
  protected final void initChannel(final Channel channel) {
    Objects.requireNonNull(channel);
    final ChannelPipeline channelPipeline = Objects.requireNonNull(channel.pipeline());
    channelPipeline.addLast(HttpServerCodec.class.getName(),
                            new HttpServerCodec());
    channelPipeline.addLast(HttpObjectToContainerRequestDecoder.class.getName(),
                            new HttpObjectToContainerRequestDecoder(this.baseUri));
    channelPipeline.addLast(eventExecutorGroup,
                            HttpContainerRequestHandlingResponseWriter.class.getName(),
                            new HttpContainerRequestHandlingResponseWriter(this.applicationHandler));
                            
  }
  
}
