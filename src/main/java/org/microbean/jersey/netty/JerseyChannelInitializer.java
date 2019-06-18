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

import java.util.function.BiFunction;

import javax.ws.rs.core.SecurityContext;

import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler; // for javadoc only
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;

import io.netty.handler.logging.LoggingHandler;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import io.netty.handler.stream.ChunkedWriteHandler;

import org.glassfish.jersey.server.ApplicationHandler;

/**
 * A {@link ChannelInitializer} that sets up <a
 * href="https://jersey.github.io/">Jersey</a> integration.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see ChannelInitializer
 *
 * @see #initChannel(Channel)
 */
public class JerseyChannelInitializer extends ChannelInitializer<Channel> {


  /*
   * Instance fields.
   */

  
  private final URI baseUri;

  private final SslContext sslContext;

  private final ApplicationHandler applicationHandler;

  private final BiFunction<? super ChannelHandlerContext, ? super HttpRequest, ? extends SecurityContext> securityContextBiFunction;
  

  /*
   * Constructors.
   */


  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param sslContext an {@link SslContext} that may be used to
   * {@linkplain #createSslHandler(SslContext, ByteBufAllocator)
   * create an <code>SslHandler</code>}; may be {@code null}
   *
   * @param applicationHandler the {@link ApplicationHandler}
   * representing Jersey; may be {@code null} in which case a
   * {@linkplain ApplicationHandler#ApplicationHandler() new
   * <code>ApplicationHandler</code>} will be used instead
   *
   * @param securityContextBiFunction a {@link BiFunction} that
   * returns a {@link SecurityContext} when supplied with a {@link
   * ChannelHandlerContext} and an {@link HttpRequest}; may be {@code
   * null}
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final ApplicationHandler applicationHandler,
                                  final BiFunction<? super ChannelHandlerContext, ? super HttpRequest, ? extends SecurityContext> securityContextBiFunction) {
    super();
    this.baseUri = baseUri;
    this.sslContext = sslContext;
    this.applicationHandler = applicationHandler == null ? new ApplicationHandler() : applicationHandler;
    this.securityContextBiFunction = securityContextBiFunction;
  }


  /*
   * Instance methods.
   */
  

  /**
   * Sets up Netty with Jersey application support.
   *
   * @param channel the {@link Channel} representing a networking
   * connection to the outside world; may be {@code null} in which
   * case no action will be taken
   *
   * @see Channel#pipeline()
   *
   * @see ChannelPipeline#addLast(String, ChannelHandler)
   *
   * @see #preInitChannel(Channel)
   *
   * @see #postInitChannel(Channel)
   */
  @Override
  public final void initChannel(final Channel channel) {
    if (channel != null) {
      this.preInitChannel(channel);
      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;
      if (this.sslContext != null) {
        final SslHandler sslHandler = createSslHandler(this.sslContext, channel.alloc());
        if (sslHandler != null) {
          channelPipeline.addLast(sslHandler.getClass().getSimpleName(), sslHandler);
        }
      }
      channelPipeline.addLast(HttpServerCodec.class.getSimpleName(), new HttpServerCodec());
      channelPipeline.addLast(HttpServerExpectContinueHandler.class.getSimpleName(), new HttpServerExpectContinueHandler());
      channelPipeline.addLast(ChunkedWriteHandler.class.getSimpleName(), new ChunkedWriteHandler());
      channelPipeline.addLast(JerseyChannelInboundHandler.class.getSimpleName(), new JerseyChannelInboundHandler(this.baseUri, this.applicationHandler, this.securityContextBiFunction));
      this.postInitChannel(channel);
    }
  }


  /**
   * A hook for performing {@link Channel} initialization before the
   * Jersey integration is set up.
   *
   * <p>This implementation {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) installs} a
   * {@link LoggingHandler}.</p>
   *
   * <p>Overrides must not call {@link #initChannel(Channel)} or an
   * infinite loop will result.</p>
   *
   * @param channel the {@link Channel} being {@linkplain
   * #initChannel(Channel) initialized}; may be {@code null} in which
   * case no action will be taken
   *
   * @see #initChannel(Channel)
   *
   * @see ChannelPipeline#addLast(String, ChannelHandler)
   */
  protected void preInitChannel(final Channel channel) {
    if (channel != null) {
      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;
      channelPipeline.addLast("LoggingHandler", new LoggingHandler());
    }
  }

  /**
   * A hook for performing {@link Channel} initialization after the
   * Jersey integration is set up.
   *
   * <p>This implementation does nothing.</p>
   *
   * <p>Overrides must not call {@link #initChannel(Channel)} or an
   * infinite loop will result.</p>
   *
   * @param channel the {@link Channel} being {@linkplain
   * #initChannel(Channel) initialized}; may be {@code null} in which
   * case no action will be taken
   *
   * @see #initChannel(Channel)
   *
   * @see ChannelPipeline#addLast(String, ChannelHandler)
   */
  protected void postInitChannel(final Channel channel) {

  }

  /**
   * Creates and returns a new {@link SslHandler} when invoked.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * @param sslContext the {@link SslContext} that may assist in the
   * creation; must not be {@code null}
   *
   * @param byteBufAllocator a {@link ByteBufAllocator} that may
   * assist in the creation; must not be {@code null}
   *
   * @return a new {@link SslHandler}; never {@code null}
   *
   * @see SslContext#newHandler(ByteBufAllocator)
   */
  protected SslHandler createSslHandler(final SslContext sslContext, final ByteBufAllocator byteBufAllocator) {
    return sslContext.newHandler(byteBufAllocator);
  }

}
