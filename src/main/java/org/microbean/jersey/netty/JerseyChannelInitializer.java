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

import java.net.URI;

import java.util.Objects;

import java.util.function.Supplier;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.Configuration;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler; // for javadoc only
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.SourceCodec; // for javadoc only
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;

import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

import org.glassfish.jersey.internal.inject.Bindings;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.ReferencingFactory;

import org.glassfish.jersey.process.internal.RequestScoped;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest; // for javadoc only

import org.microbean.jersey.netty.AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator;

/**
 * A {@link ChannelInitializer} that {@linkplain
 * #initChannel(Channel) initializes
 * <code>Channel</code>s} by configuring their {@link
 * ChannelPipeline}s to include {@link ChannelHandler}s that transform
 * Netty HTTP and HTTP/2 messages into Jersey {@link
 * ContainerRequest}s that are then {@linkplain
 * ApplicationHandler#handle(ContainerRequest) handled} by an {@link
 * ApplicationHandler}.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #initChannel(Channel)
 *
 * @see ContainerRequest
 *
 * @see ApplicationHandler#handle(ContainerRequest)
 *
 * @see HttpObjectToContainerRequestDecoder
 *
 * @see HttpContainerRequestHandlingResponseWriter
 *
 * @see Http2StreamFrameToContainerRequestDecoder
 *
 * @see Http2ContainerRequestHandlingResponseWriter
 */
public class JerseyChannelInitializer extends ChannelInitializer<Channel> {


  /*
   * Static fields.
   */


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

  private final boolean http2Support;

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

  private final EventExecutorGroup jerseyEventExecutorGroup;

  private final Supplier<? extends ApplicationHandler> applicationHandlerSupplier;

  private final int flushThreshold;

  private final ByteBufCreator byteBufCreator;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param jaxrsApplication the {@link Application} to run; may be
   * {@code null} somewhat pathologically
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final Application jaxrsApplication) {
    this(baseUri,
         sslContext,
         true,
         toSupplier(jaxrsApplication));
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final ApplicationHandler applicationHandler) {
    this(baseUri,
         sslContext,
         true,
         toSupplier(applicationHandler));
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param applicationHandlerSupplier a {@link Supplier} of an {@link
   * ApplicationHandler} representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final Supplier<? extends ApplicationHandler> applicationHandlerSupplier) {
    this(baseUri,
         sslContext,
         true,
         applicationHandlerSupplier);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param jaxrsApplication the {@link Application} to run; may be
   * {@code null} somewhat pathologically
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final Application jaxrsApplication) {
    this(baseUri,
         sslContext,
         http2Support,
         20971520L, /* 20 MB; arbitrary */
         null, /* Jersey EventExecutorGroup will be defaulted */
         true, /* use Jersey injection */
         toSupplier(jaxrsApplication),
         8192, /* 8K; arbitrary */
         Unpooled::wrappedBuffer);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final ApplicationHandler applicationHandler) {
    this(baseUri,
         sslContext,
         http2Support,
         20971520L, /* 20 MB; arbitrary */
         null, /* Jersey EventExecutorGroup will be defaulted */
         true, /* use Jersey injection */
         toSupplier(applicationHandler),
         8192, /* 8K; arbitrary */
         Unpooled::wrappedBuffer);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param applicationHandlerSupplier a {@link Supplier} of an {@link
   * ApplicationHandler} representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final Supplier<? extends ApplicationHandler> applicationHandlerSupplier) {
    this(baseUri,
         sslContext,
         http2Support,
         20971520L, /* 20 MB; arbitrary */
         null, /* Jersey EventExecutorGroup will be defaulted */
         true, /* use Jersey injection */
         applicationHandlerSupplier,
         8192, /* 8K; arbitrary */
         Unpooled::wrappedBuffer);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param maxIncomingContentLength in the case of HTTP to HTTP/2
   * upgrades, a {@code long} that governs the maximum permitted
   * incoming entity length in bytes; if less than {@code 0} then
   * {@link Long#MAX_VALUE} will be used instead; if exactly {@code 0}
   * then if the HTTP message containing the upgrade header is
   * something like a {@code POST} it will be rejected with a {@code
   * 413} error code; ignored entirely if {@code http2Support} is
   * {@code false}
   *
   * @param jerseyEventExecutorGroup an {@link EventExecutorGroup}
   * that will manage the thread on which an {@link
   * ApplicationHandler#handle(ContainerRequest)} call will occur; may
   * be {@code null} in which case a new {@link
   * DefaultEventExecutorGroup} will be used instead
   *
   * @param jaxrsApplication the {@link Application} to run; may be
   * {@code null} somewhat pathologically
   *
   * @param flushThreshold the minimum number of bytes that an {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}
   * returned by an implementation of the {@link
   * AbstractContainerRequestHandlingResponseWriter#createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#flush()
   * flush} may take place; if less than {@code 0} {@code 0} will be
   * used instead; if {@link Integer#MAX_VALUE} then it is suggested
   * that no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be
   * {@linkplain
   * AbstractContainerRequestHandlingResponseWriter#AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * passed to an
   * <code>AbstractContainerRequestHandlingResponseWriter</code>}
   * implementation; may be {@code null}
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final long maxIncomingContentLength,
                                  final EventExecutorGroup jerseyEventExecutorGroup,
                                  final Application jaxrsApplication,
                                  final int flushThreshold,
                                  final ByteBufCreator byteBufCreator) {
    this(baseUri,
         sslContext,
         http2Support,
         maxIncomingContentLength,
         jerseyEventExecutorGroup,
         true, /* use Jersey injection */
         toSupplier(jaxrsApplication),
         flushThreshold,
         byteBufCreator);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param maxIncomingContentLength in the case of HTTP to HTTP/2
   * upgrades, a {@code long} that governs the maximum permitted
   * incoming entity length in bytes; if less than {@code 0} then
   * {@link Long#MAX_VALUE} will be used instead; if exactly {@code 0}
   * then if the HTTP message containing the upgrade header is
   * something like a {@code POST} it will be rejected with a {@code
   * 413} error code; ignored entirely if {@code http2Support} is
   * {@code false}
   *
   * @param jerseyEventExecutorGroup an {@link EventExecutorGroup}
   * that will manage the thread on which an {@link
   * ApplicationHandler#handle(ContainerRequest)} call will occur; may
   * be {@code null} in which case a new {@link
   * DefaultEventExecutorGroup} will be used instead
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @param flushThreshold the minimum number of bytes that an {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}
   * returned by an implementation of the {@link
   * AbstractContainerRequestHandlingResponseWriter#createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#flush()
   * flush} may take place; if less than {@code 0} {@code 0} will be
   * used instead; if {@link Integer#MAX_VALUE} then it is suggested
   * that no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be
   * {@linkplain
   * AbstractContainerRequestHandlingResponseWriter#AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * passed to an
   * <code>AbstractContainerRequestHandlingResponseWriter</code>}
   * implementation; may be {@code null}
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, ApplicationHandler, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final long maxIncomingContentLength,
                                  final EventExecutorGroup jerseyEventExecutorGroup,
                                  final ApplicationHandler applicationHandler,
                                  final int flushThreshold,
                                  final ByteBufCreator byteBufCreator) {
    this(baseUri,
         sslContext,
         http2Support,
         maxIncomingContentLength,
         jerseyEventExecutorGroup,
         true, /* use Jersey injection */
         toSupplier(applicationHandler),
         flushThreshold,
         byteBufCreator);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param maxIncomingContentLength in the case of HTTP to HTTP/2
   * upgrades, a {@code long} that governs the maximum permitted
   * incoming entity length in bytes; if less than {@code 0} then
   * {@link Long#MAX_VALUE} will be used instead; if exactly {@code 0}
   * then if the HTTP message containing the upgrade header is
   * something like a {@code POST} it will be rejected with a {@code
   * 413} error code; ignored entirely if {@code http2Support} is
   * {@code false}
   *
   * @param jerseyEventExecutorGroup an {@link EventExecutorGroup}
   * that will manage the thread on which an {@link
   * ApplicationHandler#handle(ContainerRequest)} call will occur; may
   * be {@code null} in which case a new {@link
   * DefaultEventExecutorGroup} will be used instead
   *
   * @param useJerseyInjection if {@code true} then certain Netty
   * constructs like {@link ChannelHandlerContext} will be made
   * available for dependency injection in user applications using
   * Jersey's native dependency injection facilities; if {@code false}
   * then these facilities will not be used or referenced
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @param flushThreshold the minimum number of bytes that an {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}
   * returned by an implementation of the {@link
   * AbstractContainerRequestHandlingResponseWriter#createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#flush()
   * flush} may take place; if less than {@code 0} {@code 0} will be
   * used instead; if {@link Integer#MAX_VALUE} then it is suggested
   * that no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be
   * {@linkplain
   * AbstractContainerRequestHandlingResponseWriter#AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * passed to an
   * <code>AbstractContainerRequestHandlingResponseWriter</code>}
   * implementation; may be {@code null}
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final long maxIncomingContentLength,
                                  final EventExecutorGroup jerseyEventExecutorGroup,
                                  final boolean useJerseyInjection,
                                  final ApplicationHandler applicationHandler,
                                  final int flushThreshold,
                                  final ByteBufCreator byteBufCreator) {
    this(baseUri,
         sslContext,
         http2Support,
         maxIncomingContentLength,
         jerseyEventExecutorGroup,
         useJerseyInjection,
         toSupplier(applicationHandler),
         flushThreshold,
         byteBufCreator);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param sslContext an {@link SslContext}; may be {@code null} in
   * which case network communications will occur in plain text
   *
   * @param http2Support if HTTP/2 support (including upgrades, prior
   * knowledge, h2c, etc.) should be enabled
   *
   * @param maxIncomingContentLength in the case of HTTP to HTTP/2
   * upgrades, a {@code long} that governs the maximum permitted
   * incoming entity length in bytes; if less than {@code 0} then
   * {@link Long#MAX_VALUE} will be used instead; if exactly {@code 0}
   * then if the HTTP message containing the upgrade header is
   * something like a {@code POST} it will be rejected with a {@code
   * 413} error code; ignored entirely if {@code http2Support} is
   * {@code false}
   *
   * @param jerseyEventExecutorGroup an {@link EventExecutorGroup}
   * that will manage the thread on which an {@link
   * ApplicationHandler#handle(ContainerRequest)} call will occur; may
   * be {@code null} in which case a new {@link
   * DefaultEventExecutorGroup} will be used instead
   *
   * @param useJerseyInjection if {@code true} then certain Netty
   * constructs like {@link ChannelHandlerContext} will be made
   * available for dependency injection in user applications using
   * Jersey's native dependency injection facilities; if {@code false}
   * then these facilities will not be used or referenced
   *
   * @param applicationHandlerSupplier a {@link Supplier} of an {@link
   * ApplicationHandler} representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not.
   *
   * @param flushThreshold the minimum number of bytes that an {@link
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}
   * returned by an implementation of the {@link
   * AbstractContainerRequestHandlingResponseWriter#createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#flush()
   * flush} may take place; if less than {@code 0} {@code 0} will be
   * used instead; if {@link Integer#MAX_VALUE} then it is suggested
   * that no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be
   * {@linkplain
   * AbstractContainerRequestHandlingResponseWriter#AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * passed to an
   * <code>AbstractContainerRequestHandlingResponseWriter</code>}
   * implementation; may be {@code null}
   *
   * @see ContainerRequest
   *
   * @see SslContext
   *
   * @see
   * HttpServerUpgradeHandler#HttpServerUpgradeHandler(SourceCodec,
   * UpgradeCodecFactory, int)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see AbstractContainerRequestHandlingResponseWriter
   *
   * @see HttpObjectToContainerRequestDecoder
   *
   * @see HttpContainerRequestHandlingResponseWriter
   *
   * @see Http2StreamFrameToContainerRequestDecoder
   *
   * @see Http2ContainerRequestHandlingResponseWriter
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final boolean http2Support,
                                  final long maxIncomingContentLength,
                                  final EventExecutorGroup jerseyEventExecutorGroup,
                                  final boolean useJerseyInjection,
                                  Supplier<? extends ApplicationHandler> applicationHandlerSupplier,
                                  final int flushThreshold,
                                  final ByteBufCreator byteBufCreator) {
    super();
    this.baseUri = baseUri;
    this.sslContext = sslContext;
    this.http2Support = http2Support;
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
    if (jerseyEventExecutorGroup == null) {
      this.jerseyEventExecutorGroup = new DefaultEventExecutorGroup(Runtime.getRuntime().availableProcessors()); // no idea how to size this
    } else {
      this.jerseyEventExecutorGroup = jerseyEventExecutorGroup;
    }
    if (applicationHandlerSupplier == null) {
      final ApplicationHandler applicationHandler = new ApplicationHandler();
      applicationHandlerSupplier = () -> applicationHandler;
    }
    if (useJerseyInjection) {
      // The idiom you see before you is apparently the right and only
      // way to install non-proxiable objects into request scope: you
      // install a factory that makes factories of mutable references
      // that produce the object you want, then elsewhere set the
      // payload of those references when you're actually in request
      // scope.  Really.  You'll find this pattern throughout the
      // Jersey codebase.  We follow suit here.
      final InjectionManager injectionManager = applicationHandlerSupplier.get().getInjectionManager();
      if (injectionManager != null) {
        injectionManager.register(Bindings.supplier(ChannelHandlerContextReferencingFactory.class)
                                    .to(ChannelHandlerContext.class)
                                    .proxy(false)
                                    .in(RequestScoped.class));
        injectionManager.register(Bindings.supplier(ReferencingFactory.<ChannelHandlerContext>referenceFactory())
                                    .to(ChannelHandlerContextReferencingFactory.genericRefType)
                                    .in(RequestScoped.class));
        injectionManager.register(Bindings.supplier(HttpRequestReferencingFactory.class)
                                    .to(HttpRequest.class)
                                    .proxy(false)
                                    .in(RequestScoped.class));
        injectionManager.register(Bindings.supplier(ReferencingFactory.<HttpRequest>referenceFactory())
                                    .to(HttpRequestReferencingFactory.genericRefType)
                                    .in(RequestScoped.class));
        if (http2Support) {
          injectionManager.register(Bindings.supplier(Http2HeadersFrameReferencingFactory.class)
                                      .to(Http2HeadersFrame.class)
                                      .proxy(false)
                                      .in(RequestScoped.class));
          injectionManager.register(Bindings.supplier(ReferencingFactory.<Http2HeadersFrame>referenceFactory())
                                      .to(Http2HeadersFrameReferencingFactory.genericRefType)
                                      .in(RequestScoped.class));
        }
      }
    }
    this.applicationHandlerSupplier = applicationHandlerSupplier;
    this.flushThreshold = Math.max(0, flushThreshold);
    this.byteBufCreator = byteBufCreator;
  }


  /*
   * Instance methods.
   */


  /**
   * Returns the {@link EventExecutorGroup} that this {@link
   * JerseyChannelInitializer} will use to offload blocking work from
   * the Netty event loop.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * @return a non-{@code null} {@link EventExecutorGroup}
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   */
  public final EventExecutorGroup getJerseyEventExecutorGroup() {
    return this.jerseyEventExecutorGroup;
  }

  /**
   * Returns the {@link URI} that was {@linkplain
   * #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * supplied at construction time}.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @return the {@link URI} that was {@linkplain
   * #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   * supplied at construction time}, or {@code null}
   *
   * @see #JerseyChannelInitializer(URI, SslContext, boolean, long,
   * EventExecutorGroup, boolean, Supplier, int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   */
  public final URI getBaseUri() {
    return this.baseUri;
  }

  /**
   * Initializes the supplied {@link Channel} using an appropriate
   * sequencing of several {@link ChannelHandler}s and other Netty
   * utility classes.
   *
   * <p>The {@link ChannelHandler}s and other classes involved include:</p>
   *
   * <ul>
   *
   * <li>{@link HttpObjectToContainerRequestDecoder}</li>
   *
   * <li>{@link HttpContainerRequestHandlingResponseWriter}</li>
   *
   * <li>{@link Http2StreamFrameToContainerRequestDecoder}</li>
   *
   * <li>{@link Http2ContainerRequestHandlingResponseWriter}</li>
   *
   * <li>{@link HttpServerCodec}</li>
   *
   * <li>{@link UpgradeCodecFactory}</li>
   *
   * <li>{@link UpgradeCodec}</li>
   *
   * <li>{@link Http2FrameCodecBuilder}</li>
   *
   * <li>{@link Http2FrameCodec}</li>
   *
   * <li>{@link Http2ServerUpgradeCodec}</li>
   *
   * <li>{@link Http2MultiplexHandler}</li>
   *
   * <li>{@link HttpServerUpgradeHandler}</li>
   *
   * <li>{@link CleartextHttp2ServerUpgradeHandler}</li>
   *
   * <li>{@link HttpServerExpectContinueHandler}</li>
   *
   * <li>{@link HttpNegotiationHandler}</li>
   *
   * </ul>
   *
   * <p>All of these classes collaborate to form a {@link
   * ChannelPipeline} that can handle HTTP 1.0, HTTP 1.1 and HTTP/2
   * scenarios, including upgrades.</p>
   *
   * @param channel the {@link Channel} to initialize; must not be
   * {@code null}
   *
   * @exception NullPointerException if {@code channel} is {@code
   * null}, or if the return value of {@link Channel#pipeline()
   * channel.pipeline()} is {@code null}
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

      final HttpServerCodec httpServerCodec = new HttpServerCodec();

      if (this.http2Support) {

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
                                              new Http2MultiplexHandler(new Http2JerseyChannelInitializer(getJerseyEventExecutorGroup(),
                                                                                                          baseUri,
                                                                                                          applicationHandlerSupplier,
                                                                                                          flushThreshold,
                                                                                                          byteBufCreator)));
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
        //
        //   * A private internal handler created inside the
        //     CleartextHttp2ServerUpgradeHandler class is added first
        //     (it will see if a prior knowledge situation is
        //     occurring and then will decide to do with the handlers
        //     supplied in this constructor; see
        //     https://github.com/netty/netty/blob/d8b1a2d93f556a08270e6549bf7f91b3b09f24bb/codec-http2/src/main/java/io/netty/handler/codec/http2/CleartextHttp2ServerUpgradeHandler.java#L74-L100
        //     for details)
        //
        //   * The first handler, an HttpServerCodec, MAY be added to
        //     the pipeline next (it will, if the prior knowledge
        //     handler doesn't bypass and remove it, be responsible
        //     for interpreting an HTTP 1.1 message that might be
        //     destined for an upgrade to HTTP/2)
        //
        //   * The second handler, an HttpServerUpgradeHandler,
        //     created by us above, will be added next if indeed the
        //     HttpServerCodec was actually added by the prior
        //     knowledge handler (it will read an HttpMessage (an HTTP
        //     1.1 message) and will see if it represents an upgrade
        //     request).  If this handler is actually added to the
        //     pipeline, then "prior knowledge" is not in effect.
        //
        //   * The third handler is held in reserve to be used only in
        //     those cases where prior knowledge is in effect; see
        //     https://github.com/netty/netty/blob/d8b1a2d93f556a08270e6549bf7f91b3b09f24bb/codec-http2/src/main/java/io/netty/handler/codec/http2/CleartextHttp2ServerUpgradeHandler.java#L90-L95
        //     for details and
        //     https://http2.github.io/http2-spec/#known-http for
        //     specification references.
        //
        // This API is tremendously confusing; it's not just you.
        final CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler =
          new CleartextHttp2ServerUpgradeHandler(httpServerCodec,
                                                 httpServerUpgradeHandler,
                                                 new ChannelInitializer<Channel>() {
                                                   @Override
                                                   public final void initChannel(final Channel channel) {
                                                     // "Prior
                                                     // knowledge" is in
                                                     // effect.  See
                                                     // https://http2.github.io/http2-spec/#known-http.
                                                     final ChannelPipeline channelPipeline = channel.pipeline();
                                                     channelPipeline.addLast(Http2FrameCodec.class.getSimpleName(),
                                                                             Http2FrameCodecBuilder.forServer().build());
                                                     channelPipeline.addLast(Http2MultiplexHandler.class.getSimpleName(),
                                                                             new Http2MultiplexHandler(new Http2JerseyChannelInitializer(getJerseyEventExecutorGroup(),
                                                                                                                                         baseUri,
                                                                                                                                         applicationHandlerSupplier,
                                                                                                                                         flushThreshold,
                                                                                                                                         byteBufCreator)));
                                                   }
                                                 });
        channelPipeline.addLast(cleartextHttp2ServerUpgradeHandler);

        // We add a CONDITIONAL handler for the (probably very common)
        // case where no one (a) connected with HTTP/2 or (b) asked
        // for an HTTP/2 upgrade.  In this case after all the
        // shenanigans we just jumped through we're just a regular old
        // common HTTP 1.1 connection.  Strangely, we have to handle
        // this in Netty as a special case even though it is likely to
        // be the most common one.
        channelPipeline.addLast(new SimpleChannelInboundHandler<HttpMessage>() {
            @Override
            protected final void channelRead0(final ChannelHandlerContext channelHandlerContext,
                                              final HttpMessage httpMessage)
              throws Exception {
              final ChannelPipeline channelPipeline = channelHandlerContext.pipeline();

              // If we're actually called then we know that in "front"
              // of us is an HttpServerCodec because otherwise we
              // wouldn't have been called (note that our event is an
              // HttpMessage).  Now that we know this is going to be
              // HTTP 1.1 with no upgrades, replace *this* handler
              // we're "in" now with a handler that deals with HTTP
              // 100-class statuses...
              channelPipeline.replace(this,
                                      HttpServerExpectContinueHandler.class.getSimpleName(),
                                      new HttpServerExpectContinueHandler());

              // ...and then after that add the "real" initializer (a
              // JerseyChannelSubInitializer instance, defined below
              // in this source file) that will install a
              // ChunkedWriteHandler followed by the main Jersey
              // integration.
              channelPipeline.addLast("HttpJerseyChannelInitializer",
                                      new HttpJerseyChannelInitializer(getJerseyEventExecutorGroup(),
                                                                       baseUri,
                                                                       applicationHandlerSupplier,
                                                                       flushThreshold,
                                                                       byteBufCreator));

              // Forward the event on as we never touched it.
              channelHandlerContext.fireChannelRead(ReferenceCountUtil.retain(httpMessage));
            }
          });
      } else {
        channelPipeline.addLast(HttpServerCodec.class.getSimpleName(),
                                httpServerCodec);
        channelPipeline.addLast(HttpServerExpectContinueHandler.class.getSimpleName(),
                                new HttpServerExpectContinueHandler());
        channelPipeline.addLast("HttpJerseyChannelInitializer",
                                new HttpJerseyChannelInitializer(this.getJerseyEventExecutorGroup(),
                                                                 baseUri,
                                                                 applicationHandlerSupplier,
                                                                 flushThreshold,
                                                                 byteBufCreator));
      }


    } else {

      // The SSL handler decodes TLS stuff...
      channelPipeline.addLast(sslHandler.getClass().getSimpleName(),
                              sslHandler);

      // ...then the HttpNegotiationHandler does ALPN
      // (Application-Level Protocol Negotiation) to figure out
      // whether it's HTTP 1.1 or HTTP/2; see the private inner
      // class below for details.
      channelPipeline.addLast(HttpNegotiationHandler.class.getSimpleName(),
                              new HttpNegotiationHandler(this.getJerseyEventExecutorGroup(),
                                                         baseUri,
                                                         applicationHandlerSupplier,
                                                         flushThreshold,
                                                         byteBufCreator));

    }

  }

  /**
   * Creates and returns a new {@link SslHandler} when invoked.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>This implementation calls {@link
   * SslContext#newHandler(ByteBufAllocator)
   * sslContext.newHandler(byteBufAllocator)} and returns the
   * result.</p>
   *
   * @param sslContext the {@link SslContext} that may assist in the
   * creation; must not be {@code null}
   *
   * @param byteBufAllocator a {@link ByteBufAllocator} that may
   * assist in the creation; must not be {@code null}
   *
   * @return a new {@link SslHandler}; never {@code null}
   *
   * @exception NullPointerException if {@code sslContext} is {@code null}
   *
   * @see SslContext#newHandler(ByteBufAllocator)
   */
  protected SslHandler createSslHandler(final SslContext sslContext, final ByteBufAllocator byteBufAllocator) {
    return sslContext.newHandler(byteBufAllocator);
  }


  /*
   * Static methods.
   */


  private static final Supplier<? extends ApplicationHandler> toSupplier(final Application application) {
    return toSupplier(application == null ? new ApplicationHandler() : new ApplicationHandler(application));
  }

  private static final Supplier<? extends ApplicationHandler> toSupplier(final ApplicationHandler suppliedApplicationHandler) {
    final ApplicationHandler applicationHandler = suppliedApplicationHandler == null ? new ApplicationHandler() : suppliedApplicationHandler;
    return () -> applicationHandler;
  }

  private static final Configuration returnNullConfiguration() {
    return null;
  }


  /*
   * Inner and nested classes.
   */



  /**
   * A {@link ChannelInitializer} that {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) adds} an {@link
   * HttpObjectToContainerRequestDecoder} followed by an {@link
   * HttpContainerRequestHandlingResponseWriter} (which is added with
   * its own {@link EventExecutorGroup}).
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ChannelInitializer
   */
  private static final class HttpJerseyChannelInitializer extends ChannelInitializer<Channel> {

    private final EventExecutorGroup jerseyEventExecutorGroup;

    private final URI baseUri;

    private final Supplier<? extends ApplicationHandler> applicationHandlerSupplier;

    private final Supplier<? extends Configuration> configurationSupplier;

    private final int flushThreshold;

    private final ByteBufCreator byteBufCreator;

    /**
     * Creates a new {@link HttpJerseyChannelInitializer}.
     */
    private HttpJerseyChannelInitializer(final EventExecutorGroup jerseyEventExecutorGroup,
                                         final URI baseUri,
                                         final Supplier<? extends ApplicationHandler> applicationHandlerSupplier,
                                         final int flushThreshold,
                                         final ByteBufCreator byteBufCreator) {
      super();
      this.jerseyEventExecutorGroup = Objects.requireNonNull(jerseyEventExecutorGroup);
      this.baseUri = baseUri;
      this.applicationHandlerSupplier = applicationHandlerSupplier;
      if (applicationHandlerSupplier == null) {
        this.configurationSupplier = JerseyChannelInitializer::returnNullConfiguration;
      } else {
        this.configurationSupplier = () -> applicationHandlerSupplier.get().getConfiguration();
      }
      this.flushThreshold = Math.max(0, flushThreshold);
      this.byteBufCreator = byteBufCreator;
    }

    /**
     * {@linkplain ChannelPipeline#addLast(String, ChannelHandler)
     * Adds} an {@link
     * HttpObjectToContainerRequestDecoder} followed by an {@link
     * HttpContainerRequestHandlingResponseWriter} (which is added with
     * its own {@link EventExecutorGroup})
     *
     * @param channel the {@link Channel} being configured; must
     * not be {@code null}
     *
     */
    @Override
    protected final void initChannel(final Channel channel) {
      final ChannelPipeline channelPipeline = channel.pipeline();
      channelPipeline.addLast(HttpObjectToContainerRequestDecoder.class.getSimpleName(),
                              new HttpObjectToContainerRequestDecoder(baseUri, this.configurationSupplier));
      channelPipeline.addLast(jerseyEventExecutorGroup,
                              HttpContainerRequestHandlingResponseWriter.class.getSimpleName(),
                              new HttpContainerRequestHandlingResponseWriter(applicationHandlerSupplier,
                                                                             flushThreshold,
                                                                             byteBufCreator));
    }

  }


  /**
   * A {@link ChannelInitializer} that {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) adds} an {@link
   * Http2StreamFrameToContainerRequestDecoder} followed by an {@link
   * Http2ContainerRequestHandlingResponseWriter} (which is added with
   * its own {@link EventExecutorGroup}).
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ChannelInitializer
   */
  private static final class Http2JerseyChannelInitializer extends ChannelInitializer<Channel> {

    private final EventExecutorGroup jerseyEventExecutorGroup;

    private final URI baseUri;

    private final Supplier<? extends ApplicationHandler> applicationHandlerSupplier;

    private final Supplier<? extends Configuration> configurationSupplier;

    private final int flushThreshold;

    private final ByteBufCreator byteBufCreator;

    /**
     * Creates a new {@link Http2JerseyChannelInitializer}.
     */
    private Http2JerseyChannelInitializer(final EventExecutorGroup jerseyEventExecutorGroup,
                                          final URI baseUri,
                                          final Supplier<? extends ApplicationHandler> applicationHandlerSupplier,
                                          final int flushThreshold,
                                          final ByteBufCreator byteBufCreator) {
      super();
      this.jerseyEventExecutorGroup = Objects.requireNonNull(jerseyEventExecutorGroup);
      this.baseUri = baseUri;
      this.applicationHandlerSupplier = applicationHandlerSupplier;
      if (applicationHandlerSupplier == null) {
        this.configurationSupplier = JerseyChannelInitializer::returnNullConfiguration;
      } else {
        this.configurationSupplier = () -> applicationHandlerSupplier.get().getConfiguration();
      }
      this.flushThreshold = Math.max(0, flushThreshold);
      this.byteBufCreator = byteBufCreator;
    }

    /**
     * {@linkplain ChannelPipeline#addLast(String, ChannelHandler)
     * Adds} an {@link
     * Http2StreamFrameToContainerRequestDecoder} followed by an {@link
     * Http2ContainerRequestHandlingResponseWriter} (which is added with
     * its own {@link EventExecutorGroup}).
     *
     * @param channel the {@link Channel} being configured; must
     * not be {@code null}
     *
     * @exception NullPointerException if {@code channel} is {@code
     * null}
     */
    @Override
    protected final void initChannel(final Channel channel) {
      final ChannelPipeline channelPipeline = channel.pipeline();
      channelPipeline.addLast(Http2StreamFrameToContainerRequestDecoder.class.getSimpleName(),
                              new Http2StreamFrameToContainerRequestDecoder(baseUri, this.configurationSupplier));
      channelPipeline.addLast(jerseyEventExecutorGroup,
                              Http2ContainerRequestHandlingResponseWriter.class.getSimpleName(),
                              new Http2ContainerRequestHandlingResponseWriter(this.applicationHandlerSupplier));
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
  private static final class HttpNegotiationHandler extends ApplicationProtocolNegotiationHandler {

    private final EventExecutorGroup jerseyEventExecutorGroup;

    private final URI baseUri;

    private final Supplier<? extends ApplicationHandler> applicationHandlerSupplier;

    private final int flushThreshold;

    private final ByteBufCreator byteBufCreator;
    /**
     * Creates a new {@link HttpNegotiationHandler}.
     */
    private HttpNegotiationHandler(final EventExecutorGroup jerseyEventExecutorGroup,
                                   final URI baseUri,
                                   final Supplier<? extends ApplicationHandler> applicationHandlerSupplier,
                                   final int flushThreshold,
                                   final ByteBufCreator byteBufCreator) {
      super(ApplicationProtocolNames.HTTP_1_1);
      this.jerseyEventExecutorGroup = Objects.requireNonNull(jerseyEventExecutorGroup);
      this.baseUri = baseUri;
      this.applicationHandlerSupplier = applicationHandlerSupplier;
      this.flushThreshold = Math.max(0, flushThreshold);
      this.byteBufCreator = byteBufCreator;
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
     *
     * @exception NullPointerException if {@code
     * channelHandlerContext} or {@code protocol} is {@code null}
     *
     * @exception IllegalArgumentException if {@code protocol} is not
     * equal to the value of either the {@link
     * ApplicationProtocolNames#HTTP_1_1} or {@link
     * ApplicationProtocolNames#HTTP_2} constants
     */
    @Override
    protected final void configurePipeline(final ChannelHandlerContext channelHandlerContext, final String protocol) {
      final ChannelPipeline channelPipeline = channelHandlerContext.pipeline();
      switch (protocol) {
      case ApplicationProtocolNames.HTTP_2:
        channelPipeline.addLast(Http2FrameCodec.class.getSimpleName(),
                                Http2FrameCodecBuilder.forServer().build());
        channelPipeline.addLast(Http2MultiplexHandler.class.getSimpleName(),
                                new Http2MultiplexHandler(new Http2JerseyChannelInitializer(jerseyEventExecutorGroup,
                                                                                            baseUri,
                                                                                            applicationHandlerSupplier,
                                                                                            flushThreshold,
                                                                                            byteBufCreator)));
        break;
      case ApplicationProtocolNames.HTTP_1_1:
        channelPipeline.addLast(HttpServerCodec.class.getSimpleName(),
                                new HttpServerCodec());
        channelPipeline.addLast(HttpServerExpectContinueHandler.class.getSimpleName(),
                                new HttpServerExpectContinueHandler());
        channelPipeline.addLast("HttpJerseyChannelInitializer",
                                new HttpJerseyChannelInitializer(jerseyEventExecutorGroup,
                                                                 baseUri,
                                                                 applicationHandlerSupplier,
                                                                 flushThreshold,
                                                                 byteBufCreator));
        break;
      default:
        throw new IllegalArgumentException("protocol: " + protocol);
      }
    }

  }

}
