/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019–2020 microBean™.
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

import java.net.InetSocketAddress;

import io.netty.bootstrap.ServerBootstrap;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

import io.netty.channel.nio.NioEventLoopGroup;

import io.netty.channel.socket.nio.NioServerSocketChannel;

import org.glassfish.jersey.server.ApplicationHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class TestSpike {

  @Test
  @EnabledIfSystemProperty(named = "runBlockingTests", matches = "true")
  public void testSpike() throws Exception {
    final EventLoopGroup group = new NioEventLoopGroup();
    try {
      final ServerBootstrap serverBootstrap = new ServerBootstrap()
        .group(group)
        .channel(NioServerSocketChannel.class)
        .localAddress(new InetSocketAddress("localhost", 8080))
        .childHandler(new JerseyChannelInitializer(null, null, true, 20000000L, null, new ApplicationHandler(new Application()), 8192, null));
      final ChannelFuture bindFuture = serverBootstrap.bind();
      bindFuture.channel().closeFuture().addListener(c -> System.out.println("*** server closed"));
      bindFuture.sync();
      System.out.println("*** server started");
      Thread.sleep(20L * 60L * 1000L); // milliseconds
    } finally {
      group.shutdownGracefully().addListener(f -> System.out.println("*** eventLoopGroup shutdown")).sync();
    }
  }

}
