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

import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.handler.codec.http.HttpServerCodec;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;

import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

import org.glassfish.jersey.server.ApplicationHandler;

public class JerseyChannelInitializer extends ChannelInitializer<Channel> {


  /*
   * Static fields.
   */

  
  private static final EventExecutorGroup eventExecutorGroup =
    new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors()); // no idea how to size this


  /*
   * Instance fields.
   */

  
  /**
   * The base {@link URI} for the Jersey application.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  private final URI baseUri;

  /**
   * An {@link SslContext} that may be used to {@linkplain
   * #createSslHandler(SslContext, ByteBufAllocator) create an
   * <code>SslHandler</code>}.
   *
   * <p>This field may be {@code null}.</p>
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   *
   * @see SslContext
   *
   * @see #createSslHandler(SslContext, ByteBufAllocator)
   */
  private final SslContext sslContext;

  /**
   * In the case of HTTP to HTTP/2
   * upgrades, this field governs the maximum permitted incoming
   * entity length in bytes; if less than {@code 0} then {@link
   * Long#MAX_VALUE} will be used instead; if exactly {@code 0} then
   * if the HTTP message containing the upgrade header is something
   * like a {@code POST} it will be rejected with a {@code 413} error
   * code.
   *
   * @see HttpServerUpgradeHandler#maxContentLength()
   */
  private final long maxIncomingContentLength;

  private final ApplicationHandler applicationHandler;
  
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final long maxIncomingContentLength,
                                  final ApplicationHandler applicationHandler) {
    super();
    this.baseUri = baseUri;
    this.sslContext = sslContext;
    // It's somewhat odd that Netty's MessageAggregator class (of
    // which HttpServerUpgradeHandler is a subclass) expresses a
    // maximum content length as an int, when Jersey and other
    // HTTP-centric frameworks express it as a long.  We will accept a
    // long and truncate it where necessary.
    if (maxIncomingContentLength < 0L) {
      this.maxIncomingContentLength = Long.MAX_VALUE;
    } else {
      this.maxIncomingContentLength = maxIncomingContentLength;
    }
    this.applicationHandler = applicationHandler;
  }


  /*
   * Instance methods.
   */
  

  @Override
  protected final void initChannel(final Channel channel) {
    Objects.requireNonNull(channel);
    final ChannelPipeline channelPipeline = Objects.requireNonNull(channel.pipeline());

    final SslHandler sslHandler;
    if (this.sslContext == null) {
      sslHandler = null;
    } else {
      sslHandler = createSslHandler(this.sslContext, channel.alloc());
    }

    if (sslHandler == null) {

      // Plaintext.
      
      final HttpServerCodec httpServerCodec = new HttpServerCodec();

      // See https://github.com/netty/netty/issues/7079
      final int maxIncomingContentLength;
      if (this.maxIncomingContentLength >= Integer.MAX_VALUE) {
        maxIncomingContentLength = Integer.MAX_VALUE;
      } else {
        maxIncomingContentLength = (int)this.maxIncomingContentLength;
      }
      
      final UpgradeCodecFactory upgradeCodecFactory = new UpgradeCodecFactory() {
          @Override
          public final UpgradeCodec newUpgradeCodec(final CharSequence protocolName) {
            final UpgradeCodec returnValue;
            if (protocolName == null ||
                !AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocolName)) {
              returnValue = null;
            } else {
              returnValue =
                new Http2ServerUpgradeCodec(Http2FrameCodecBuilder.forServer().build(),
                                            new Http2MultiplexHandler(new Http2JerseyChannelInitializer()));
            }
            return returnValue;
          }
        };
      
      // Create a handler that will deal with HTTP 1.1-to-HTTP/2
      // upgrade scenarios.  It by itself doesn't really do anything
      // but it will be supplied to a new instance of
      // CleartextHttp2ServerUpgradeHandler.
      final HttpServerUpgradeHandler httpServerUpgradeHandler =
        new HttpServerUpgradeHandler(httpServerCodec, upgradeCodecFactory, maxIncomingContentLength);

      // Build a CleartextHttp2ServerUpgradeHandler.  This is really a
      // channel pipeline reconfigurator: it arranges things such
      // that:

      //   * A private internal handler created inside the
      //     CleartextHttp2ServerUpgradeHandler class is added first
      //     (it will see if a prior knowledge situation is occurring;
      //     see
      //     https://github.com/netty/netty/blob/d8b1a2d93f556a08270e6549bf7f91b3b09f24bb/codec-http2/src/main/java/io/netty/handler/codec/http2/CleartextHttp2ServerUpgradeHandler.java#L74-L100
      //     for details)

      //   * The first argument, an HttpServerCodec, is added next (it
      //     will, if the prior knowledge handler doesn't bypass and
      //     remove it, be responsible for interpreting an HTTP 1.1
      //     message that might be destined for an upgrade to HTTP/2)
      
      //   * The second argument, an HttpServerUpgradeHandler, created
      //     by us above, is added next (it will read an HttpMessage
      //     (an HTTP 1.1 message) and will see if it represents an
      //     upgrade request)
      
      //   * The third argument is held in reserve to be used only in
      //     those cases where the prior knowledge handler kicks in;
      //     see
      //     https://github.com/netty/netty/blob/d8b1a2d93f556a08270e6549bf7f91b3b09f24bb/codec-http2/src/main/java/io/netty/handler/codec/http2/CleartextHttp2ServerUpgradeHandler.java#L90-L95
      //     for details
      
      // This API is tremendously confusing; it's not just you.
      final CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler =
        new CleartextHttp2ServerUpgradeHandler(httpServerCodec,
                                               httpServerUpgradeHandler,
                                               new ChannelInitializer<Channel>() {
                                                 @Override
                                                 public final void initChannel(final Channel channel) {
                                                   assert channel != null;
                                                   final ChannelPipeline channelPipeline = channel.pipeline();
                                                   assert channelPipeline != null;
                                                   channelPipeline.addLast(Http2FrameCodec.class.getSimpleName(),
                                                                           Http2FrameCodecBuilder.forServer().build());
                                                   channelPipeline.addLast(Http2MultiplexHandler.class.getSimpleName(),
                                                                           new Http2MultiplexHandler(new Http2JerseyChannelInitializer()));
                                                 }
                                               });
      channelPipeline.addLast(cleartextHttp2ServerUpgradeHandler);

      // We add a handler for the (probably very common) case where
      // no one (a) connected with HTTP/2 or (b) asked for an HTTP/2
      // upgrade.  In this case after all the shenanigans we just
      // jumped through we're just a regular old common HTTP 1.1
      // connection.  Strangely, we have to handle this in Netty as
      // a special case even though it is likely to be the most
      // common one.
      channelPipeline.addLast(new SimpleChannelInboundHandler<HttpMessage>() {
          @Override
          protected final void channelRead0(final ChannelHandlerContext channelHandlerContext,
                                            final HttpMessage httpMessage)
            throws Exception {
            assert channelHandlerContext != null;
            final ChannelPipeline channelPipeline = channelHandlerContext.pipeline();
            assert channelPipeline != null;
            
            // We know that in "front" of us is an HttpServerCodec
            // because otherwise we wouldn't have been called (note
            // that our event is an HttpMessage).  Now that we know
            // this is going to be HTTP 1.1 with no upgrades, replace
            // *this* handler we're "in" now with a handler that deals
            // with HTTP 100-class statuses...
            channelPipeline.replace(this,
                                    HttpServerExpectContinueHandler.class.getSimpleName(),
                                    new HttpServerExpectContinueHandler());
            
            // ...and then after that add the "real" initializer (a
            // JerseyChannelSubInitializer instance, defined below
            // in this source file) that will install a
            // ChunkedWriteHandler followed by the main Jersey
            // integration.
            channelPipeline.addLast(HttpJerseyChannelInitializer.class.getName(),
                                    new HttpJerseyChannelInitializer());
            
            // Forward the event on as we never touched it.
            channelHandlerContext.fireChannelRead(ReferenceCountUtil.retain(httpMessage));
          }
        });
    } else {

      // The SSL handler decodes TLS stuff...
      channelPipeline.addLast(sslHandler.getClass().getSimpleName(), sslHandler);
      
      // ...then the HttpNegotiationHandler does ALPN
      // (Application-Level Protocol Negotiation) to figure out
      // whether it's HTTP 1.1 or HTTP/2; see the private inner
      // class below for details.
      channelPipeline.addLast(HttpNegotiationHandler.class.getSimpleName(), new HttpNegotiationHandler());
      
    }
                            
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

  
  /*
   * Inner and nested classes.
   */



  /**
   * A {@link ChannelInitializer} that {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) adds} TODO
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ChannelInitializer
   */
  private final class HttpJerseyChannelInitializer extends ChannelInitializer<Channel> {

    /**
     * Creates a new {@link HttpJerseyChannelInitializer}.
     */
    private HttpJerseyChannelInitializer() {
      super();
    }

    /**
     * {@linkplain ChannelPipeline#addLast(String, ChannelHandler)
     * Adds} a TODO
     *
     * @param channel the {@link Channel} being configured; must
     * not be {@code null}
     *
     */
    @Override
    protected final void initChannel(final Channel channel) {
      assert channel != null;
      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;
      channelPipeline.addLast(HttpObjectToContainerRequestDecoder.class.getName(),
                              new HttpObjectToContainerRequestDecoder(baseUri));
      channelPipeline.addLast(eventExecutorGroup,
                              HttpContainerRequestHandlingResponseWriter.class.getName(),
                              new HttpContainerRequestHandlingResponseWriter(applicationHandler));
    }

  }

  
  /**
   * A {@link ChannelInitializer} that {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) adds} TODO
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ChannelInitializer
   */
  private final class Http2JerseyChannelInitializer extends ChannelInitializer<Channel> {

    /**
     * Creates a new {@link Http2JerseyChannelInitializer}.
     */
    private Http2JerseyChannelInitializer() {
      super();
    }

    /**
     * {@linkplain ChannelPipeline#addLast(String, ChannelHandler)
     * Adds} a TODO
     *
     * @param channel the {@link Channel} being configured; must
     * not be {@code null}
     *
     */
    @Override
    protected final void initChannel(final Channel channel) {
      assert channel != null;
      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;
      channelPipeline.addLast(Http2StreamFrameToContainerRequestDecoder.class.getName(),
                              new Http2StreamFrameToContainerRequestDecoder(baseUri));
      channelPipeline.addLast(eventExecutorGroup,
                              Http2ContainerRequestHandlingResponseWriter.class.getName(),
                              new Http2ContainerRequestHandlingResponseWriter(applicationHandler));
    }

  }

  /**
   * An {@link ApplicationProtocolNegotiationHandler} that knows how
   * to configure a {@link ChannelPipeline} for HTTP 1.1 or HTTP/2
   * requests that require Jersey integration.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ApplicationProtocolNegotiationHandler
   */
  private final class HttpNegotiationHandler extends ApplicationProtocolNegotiationHandler {

    /**
     * Creates a new {@link HttpNegotiationHandler}.
     */
    private HttpNegotiationHandler() {
      super(ApplicationProtocolNames.HTTP_1_1);
    }

    /**
     * Sets up the {@linkplain ChannelHandlerContext#pipeline()
     * current pipeline} for HTTP 1.1 or HTTP/2 requests that require
     * Jersey integration.
     *
     * @param channelHandlerContext a {@link ChannelHandlerContext}
     * representing the current Netty execution; must not be {@code
     * null}
     *
     * @param protocol the protocol that was negotiated; must be equal
     * to either {@link ApplicationProtocolNames#HTTP_2} or {@link
     * ApplicationProtocolNames#HTTP_1_1}
     */
    @Override
    protected final void configurePipeline(final ChannelHandlerContext channelHandlerContext, final String protocol) {
      assert channelHandlerContext != null;
      assert protocol != null;
      final ChannelPipeline channelPipeline = channelHandlerContext.pipeline();
      assert channelPipeline != null;
      switch (protocol) {
      case ApplicationProtocolNames.HTTP_2:
        channelPipeline.addLast(Http2FrameCodec.class.getSimpleName(),
                                Http2FrameCodecBuilder.forServer().build());
        channelPipeline.addLast(Http2MultiplexHandler.class.getSimpleName(),
                                new Http2MultiplexHandler(new Http2JerseyChannelInitializer()));
        break;
      case ApplicationProtocolNames.HTTP_1_1:
        channelPipeline.addLast(HttpServerCodec.class.getSimpleName(),
                                new HttpServerCodec());
        channelPipeline.addLast(HttpServerExpectContinueHandler.class.getSimpleName(),
                                new HttpServerExpectContinueHandler());
        channelPipeline.addLast(HttpJerseyChannelInitializer.class.getSimpleName(),
                                new HttpJerseyChannelInitializer());
        break;
      default:
        throw new IllegalArgumentException("protocol: " + protocol);
      }
    }

  }
  
}
