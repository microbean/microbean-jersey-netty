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

import java.io.InputStream;
import java.io.IOException;

import java.net.URI;

import java.util.Objects;

import java.util.concurrent.ScheduledExecutorService;

import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.inject.Provider;

import javax.ws.rs.core.SecurityContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator; // for javadoc only
import io.netty.buffer.CompositeByteBuf;

import io.netty.channel.Channel; // for javadoc only
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.util.concurrent.EventExecutor; // for javadoc only

import org.glassfish.jersey.internal.inject.InjectionManager;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;

import org.glassfish.jersey.server.internal.ContainerUtils;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;

import org.glassfish.jersey.spi.ExecutorServiceProvider;
import org.glassfish.jersey.spi.ScheduledExecutorServiceProvider;

/**
 * A {@link SimpleChannelInboundHandler} that adapts <a
 * href="https://jersey.github.io/">Jersey</a> to Netty.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Instances of this class are safe for concurrent use by multiple
 * threads, but certain methods must be invoked {@linkplain
 * EventExecutor#inEventLoop() in the Netty event loop}.</p>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public class JerseyChannelInboundHandler extends SimpleChannelInboundHandler<HttpObject> {


  /*
   * Instance fields.
   */


  /**
   * The base {@link URI} of the Jersey application.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInboundHandler(URI, ApplicationHandler,
   * BiFunction)
   */
  private final URI baseUri;

  /**
   * The {@link ApplicationHandler} that represents Jersey.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInboundHandler(URI, ApplicationHandler,
   * BiFunction)
   */
  private final ApplicationHandler applicationHandler;

  /**
   * A {@link BiFunction} that returns a {@link SecurityContext} when
   * supplied with a {@link ChannelHandlerContext} and an {@link
   * HttpRequest}.
   *
   * <p>This field may be {@code null}.</p>
   *
   * @see #JerseyChannelInboundHandler(URI, ApplicationHandler,
   * BiFunction)
   */
  private final BiFunction<? super ChannelHandlerContext, ? super HttpRequest, ? extends SecurityContext> securityContextBiFunction;

  /**
   * A {@link ByteBufQueue} that is installed by the {@link
   * #createContainerRequest(ChannelHandlerContext, HttpRequest)}
   * method and used by the {@link
   * #messageReceived(ChannelHandlerContext, HttpContent)} method.
   *
   * <p>This field may be {@code null} at any point.</p>
   *
   * @see #createContainerRequest(ChannelHandlerContext, HttpRequest)
   *
   * @see #messageReceived(ChannelHandlerContext, HttpContent)
   *
   * @see ByteBufQueue
   *
   * @see EventLoopPinnedByteBufInputStream
   */
  private volatile ByteBufQueue byteBufQueue;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link JerseyChannelInboundHandler}.
   *
   * @param baseUri the base {@link URI} for the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param applicationHandler the Jersey {@link ApplicationHandler}
   * that will actually run the Jersey application; may be {@code
   * null} in which case a {@linkplain
   * ApplicationHandler#ApplicationHandler() new} {@link
   * ApplicationHandler} will be used instead
   *
   * @param securityContextBiFunction a {@link BiFunction} that
   * returns a {@link SecurityContext} when supplied with a {@link
   * ChannelHandlerContext} and an {@link HttpRequest}; may be {@code
   * null}
   */
  public JerseyChannelInboundHandler(final URI baseUri,
                                     final ApplicationHandler applicationHandler,
                                     final BiFunction<? super ChannelHandlerContext, ? super HttpRequest, ? extends SecurityContext> securityContextBiFunction) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
    this.applicationHandler = applicationHandler == null ? new ApplicationHandler() : applicationHandler;
    this.securityContextBiFunction = securityContextBiFunction;
  }


  /*
   * Instance methods.
   */


  /**
   * Arranges for the {@link ApplicationHandler} {@linkplain
   * #JerseyChannelInboundHandler(URI, ApplicationHandler, BiFunction)
   * supplied at construction time} to process the HTTP message
   * represented by the supplied {@link HttpObject}.
   *
   * <p>This method must be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param message the {@link HttpObject}&mdash;an {@link
   * HttpRequest} or a {@link HttpContent}&mdash;to process; must not
   * be {@code null}
   *
   * @exception Exception if an error occurs
   *
   * @see #messageReceived(ChannelHandlerContext, HttpRequest)
   *
   * @see #messageReceived(ChannelHandlerContext, HttpContent)
   */
  @Override
  protected final void channelRead0(final ChannelHandlerContext channelHandlerContext, final HttpObject message) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    if (message instanceof HttpRequest) {
      this.messageReceived(channelHandlerContext, (HttpRequest)message);
    } else if (message instanceof HttpContent) {
      this.messageReceived(channelHandlerContext, (HttpContent)message);
    } else {
      throw new IllegalArgumentException("!(message instanceof HttpRequest || message instanceof HttpContent): " + message);
    }
  }

  /**
   * Processes the supplied {@link HttpRequest} representing one
   * portion of an overall HTTP message.
   *
   * <p>Internally, a {@link ContainerRequest} is created, a new
   * {@link NettyContainerResponseWriter} is {@linkplain
   * ContainerRequest#setWriter(ContainerResponseWriter) installed on
   * it}, and the {@link ApplicationHandler#handle(ContainerRequest)}
   * method is invoked on a thread guaranteed not to be the Netty
   * event loop.</p>
   *
   * <p>This method must be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param httpRequest the {@link HttpRequest} to process; must not
   * be {@code null}
   *
   * @exception Exception if an error occurs
   *
   * @see #createContainerRequest(ChannelHandlerContext, HttpRequest)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see ExecutorServiceProvider
   *
   * @see NettyContainerResponseWriter
   */
  protected void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpRequest httpRequest) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    assert channelHandlerContext.executor().inEventLoop();

    assert this.byteBufQueue == null;

    final InjectionManager injectionManager = this.applicationHandler.getInjectionManager();
    if (injectionManager == null) {
      throw new IllegalStateException("applicationHandler.getInjectionManager() == null");
    }

    final ContainerResponseWriter writer =
      this.createNettyContainerResponseWriter(httpRequest,
                                              channelHandlerContext,
                                              () -> injectionManager.getInstance(ScheduledExecutorServiceProvider.class).getExecutorService());
    if (writer == null) {
      throw new IllegalStateException("createNettyContainerResponseWriter() == null");
    }

    final ContainerRequest containerRequest = this.createContainerRequest(channelHandlerContext, httpRequest);
    if (containerRequest == null) {
      throw new IllegalStateException("createContainerRequest() == null");
    }
    containerRequest.setWriter(writer);

    injectionManager.getInstance(ExecutorServiceProvider.class).getExecutorService().execute(() -> {
        this.applicationHandler.handle(containerRequest);
      });
  }

  /**
   * Creates and returns a new {@link NettyContainerResponseWriter}
   * when invoked.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>This implementation simply invokes the {@link
   * NettyContainerResponseWriter#NettyContainerResponseWriter(HttpRequest,
   * ChannelHandlerContext, Supplier)} constructor and returns the new
   * object.</p>
   *
   * <p>In normal usage, this method is invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * <p>This method is called by the {@link
   * #messageReceived(ChannelHandlerContext, HttpRequest)} method.
   * Overrides must not call that method or an infinite loop may
   * result.</p>
   *
   * @param httpRequest the {@link HttpRequest} being processed; must
   * not be {@code null}
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param scheduledExecutorServiceSupplier a {@link Supplier} that
   * can {@linkplain Supplier#get() supply} a {@link
   * ScheduledExecutorService}; must not be {@code null}
   *
   * @return a new {@link NettyContainerResponseWriter}; never {@code
   * null}
   *
   * @see
   * NettyContainerResponseWriter#NettyContainerResponseWriter(HttpRequest,
   * ChannelHandlerContext, Supplier)
   *
   * @see #messageReceived(ChannelHandlerContext, HttpRequest)
   */
  protected NettyContainerResponseWriter createNettyContainerResponseWriter(final HttpRequest httpRequest,
                                                                            final ChannelHandlerContext channelHandlerContext,
                                                                            final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    return new NettyContainerResponseWriter(httpRequest, channelHandlerContext, scheduledExecutorServiceSupplier);
  }

  /**
   * Processes the supplied {@link HttpContent} representing one
   * portion of an overall HTTP message.
   *
   * <p>Internally, the {@link HttpContent}'s {@linkplain
   * HttpContent#content() content}, if {@linkplain
   * ByteBuf#isReadable() readable}, is {@linkplain ByteBuf#retain()
   * retained} and {@linkplain ByteBufQueue#addByteBuf(ByteBuf) added
   * to an internal <code>ByteBufQueue</code> implementation} created
   * by the {@link #messageReceived(ChannelHandlerContext,
   * HttpRequest)} method.  Then, if the {@link HttpContent} is an
   * instance of {@link LastHttpContent}, that internal {@link
   * ByteBufQueue} is {@linkplain ByteBufQueue#close() closed}.</p>
   *
   * <p>This method must be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param httpContent the {@link HttpContent} to process; must not
   * be {@code null}
   *
   * @exception Exception if an error occurs
   */
  protected void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpContent httpContent) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    Objects.requireNonNull(httpContent);
    assert channelHandlerContext.executor().inEventLoop();

    // We only get HttpContent messages when there's actually an
    // incoming payload.  The messageReceived() override that takes an
    // HttpRequest will have set up our byteBufQueue implementation in
    // this case.
    assert this.byteBufQueue != null;

    final ByteBuf content = httpContent.content();
    assert content != null;
    assert content.refCnt() == 1 : "Unexpected refCnt: " + content.refCnt() + "; thread: " + Thread.currentThread();

    if (content.isReadable()) {
      content.retain();
      this.byteBufQueue.addByteBuf(content);
    }

    if (httpContent instanceof LastHttpContent) {
      try {
        this.byteBufQueue.close();
      } finally {
        this.byteBufQueue = null;
      }
    }
  }

  /**
   * Creates a {@link ContainerRequest} representing the supplied
   * {@link HttpRequest} and returns it.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Internally, this method sets up an internal {@link
   * ByteBufQueue} if necessary representing incoming entity data.
   * The {@link ByteBufQueue} in question is an instance of {@link
   * EventLoopPinnedByteBufInputStream} and is also {@linkplain
   * ContainerRequest#setEntityStream(InputStream) installed} on the
   * {@link ContainerRequest}.</p>
   *
   * <p>The internals of this method also {@linkplain
   * ByteBufAllocator#compositeBuffer() may allocate a
   * <code>CompositeByteBuf</code>} which will be {@linkplain
   * ByteBuf#release() released} when the {@linkplain
   * Channel#closeFuture() underlying <code>Channel</code>
   * closes}.</p>
   *
   * <p>This method must be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param httpRequest the {@link HttpRequest} to process; must not
   * be {@code null}
   *
   * @return a new {@link ContainerRequest}; never {@code null}
   *
   * @see ContainerRequest
   *
   * @see EventLoopPinnedByteBufInputStream
   *
   * @see ByteBufQueue
   */
  protected ContainerRequest createContainerRequest(final ChannelHandlerContext channelHandlerContext, final HttpRequest httpRequest) {
    Objects.requireNonNull(channelHandlerContext);
    Objects.requireNonNull(httpRequest);
    assert channelHandlerContext.executor().inEventLoop();

    final String uriString = httpRequest.uri();
    assert uriString != null;

    final SecurityContext securityContext = this.createSecurityContext(channelHandlerContext, httpRequest);
    final ContainerRequest returnValue =
      new ContainerRequest(this.baseUri,
                           baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(uriString.startsWith("/") && uriString.length() > 1 ? uriString.substring(1) : uriString)),
                           httpRequest.method().name(),
                           securityContext == null ? new SecurityContextAdapter() : securityContext,
                           new MapBackedPropertiesDelegate());

    final HttpHeaders headers = httpRequest.headers();
    if (headers != null) {
      final Iterable<? extends String> headerNames = headers.names();
      if (headerNames != null) {
        for (final String headerName : headerNames) {
          if (headerName != null) {
            returnValue.headers(headerName, headers.getAll(headerName));
          }
        }
      }
    }

    if (HttpUtil.getContentLength(httpRequest, -1L) > 0L || HttpUtil.isTransferEncodingChunked(httpRequest)) {

      final CompositeByteBuf compositeByteBuf = channelHandlerContext.alloc().compositeBuffer();
      assert compositeByteBuf != null;
      channelHandlerContext.channel().closeFuture().addListener(ignored -> compositeByteBuf.release());

      final EventLoopPinnedByteBufInputStream entityStream = new EventLoopPinnedByteBufInputStream(compositeByteBuf, channelHandlerContext.executor());
      assert this.byteBufQueue == null;
      this.byteBufQueue = entityStream;
      returnValue.setEntityStream(entityStream);

    } else {
      returnValue.setEntityStream(UnreadableInputStream.instance);
    }

    return returnValue;
  }

  /**
   * Returns a new {@link SecurityContext} instance on each invocation.
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; supplied for
   * convenience; must not be {@code null}
   *
   * @param httpRequest the current {@link HttpRequest}; supplied for
   * convenience; must not be {@code null}
   *
   * @return a new {@link SecurityContext} instance on each invocation
   *
   * @see SecurityContextAdapter
   */
  private final SecurityContext createSecurityContext(final ChannelHandlerContext channelHandlerContext, final HttpRequest httpRequest) {
    if (this.securityContextBiFunction != null) {
      return this.securityContextBiFunction.apply(channelHandlerContext, httpRequest);
    } else {
      return new SecurityContextAdapter();
    }
  }


  /*
   * Inner and nested classes.
   */


  /**
   * An {@link InputStream} that always returns {@code -1} from its
   * {@link #read()} method.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see #read()
   */
  private static final class UnreadableInputStream extends InputStream {

    /**
     * A convenient singleton instance.
     *
     * <p>This field is never {@code null}.</p>
     */
    private static final InputStream instance = new UnreadableInputStream();

    /**
     * Creates a new {@link UnreadableInputStream}.
     */
    private UnreadableInputStream() {
      super();
    }

    /**
     * Returns {@code -1} when invoked.
     *
     * @return {@code -1} when invoked
     */
    @Override
    public final int read() {
      return -1;
    }

  }

}
