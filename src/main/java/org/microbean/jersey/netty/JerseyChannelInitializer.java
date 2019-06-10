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

import javax.ws.rs.core.Application;

import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;

import io.netty.handler.logging.LoggingHandler;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import io.netty.handler.stream.ChunkedWriteHandler;

import org.glassfish.jersey.server.ApplicationHandler;

public class JerseyChannelInitializer extends ChannelInitializer<Channel> {

  private final URI baseUri;

  private final SslContext sslContext;

  private final ApplicationHandler applicationHandler;

  public JerseyChannelInitializer() {
    this(URI.create("http://ignored:0/"), null, new ApplicationHandler());
  }

  public JerseyChannelInitializer(final Application application) {
    this(URI.create("http://ignored:0/"), null, new ApplicationHandler(application));
  }
  
  public JerseyChannelInitializer(final URI baseUri,
                                  final ApplicationHandler applicationHandler) {
    this(baseUri, null, applicationHandler);
  }
  
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final ApplicationHandler applicationHandler) {
    super();
    this.baseUri = Objects.requireNonNull(baseUri);
    this.sslContext = sslContext;
    this.applicationHandler = Objects.requireNonNull(applicationHandler);
  }

  @Override
  public final void initChannel(final Channel channel) {
    Objects.requireNonNull(channel);
    this.preInitChannel(channel);
    final ChannelPipeline channelPipeline = channel.pipeline();
    if (this.sslContext != null) {
      final SslHandler sslHandler = createSslHandler(this.sslContext, channel.alloc());
      if (sslHandler != null) {
        channelPipeline.addLast(sslHandler.getClass().getSimpleName(), sslHandler);
      }
    }
    channelPipeline.addLast(HttpServerCodec.class.getSimpleName(), new HttpServerCodec());
    channelPipeline.addLast(HttpServerExpectContinueHandler.class.getSimpleName(), new HttpServerExpectContinueHandler());
    channelPipeline.addLast(ChunkedWriteHandler.class.getSimpleName(), new ChunkedWriteHandler());
    channelPipeline.addLast(JerseyChannelInboundHandler.class.getSimpleName(), new JerseyChannelInboundHandler(this.baseUri, this.applicationHandler));
    this.postInitChannel(channel);
  }

  protected void preInitChannel(final Channel channel) {
    Objects.requireNonNull(channel);
    final ChannelPipeline channelPipeline = channel.pipeline();
    channelPipeline.addLast("LoggingHandler", new LoggingHandler());
  }

  protected void postInitChannel(final Channel channel) {

  }
  
  protected SslHandler createSslHandler(final SslContext sslContext, final ByteBufAllocator byteBufAllocator) {
    return sslContext.newHandler(byteBufAllocator);
  }

}
