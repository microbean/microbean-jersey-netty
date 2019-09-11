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

import java.net.URI;

import java.util.Objects;

import java.util.concurrent.ScheduledExecutorService;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.ws.rs.core.SecurityContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;

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
public class JerseyChannelInboundHandler extends SimpleChannelInboundHandler<Object> {


  /*
   * Instance fields.
   */


  /**
   * The base {@link URI} of the Jersey application.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInboundHandler(URI, ApplicationHandler)
   */
  private final URI baseUri;

  /**
   * The {@link ApplicationHandler} that represents Jersey.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInboundHandler(URI, ApplicationHandler)
   */
  private final ApplicationHandler applicationHandler;

  /**
   * A {@link ByteBufQueue} that is installed by the {@link
   * #createContainerRequest(ChannelHandlerContext, HttpRequest)}
   * method and used by the {@link
   * #messageReceived(ChannelHandlerContext, ByteBuf, boolean)} method.
   *
   * <p>This field may be {@code null} at any point.</p>
   *
   * @see #createContainerRequest(ChannelHandlerContext, HttpRequest)
   *
   * @see #messageReceived(ChannelHandlerContext, ByteBuf, boolean)
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
   */
  public JerseyChannelInboundHandler(final URI baseUri,
                                     final ApplicationHandler applicationHandler) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
    this.applicationHandler = applicationHandler == null ? new ApplicationHandler() : applicationHandler;
  }


  /*
   * Instance methods.
   */

  /**
   * Returns {@code true} if and only if the supplied {@code message}
   * is an instance of {@link HttpRequest}, {@link HttpContent},
   * {@link Http2HeadersFrame} or {@link Http2DataFrame}.
   *
   * @param message the inbound message; may be {@code null} in which
   * case {@code false} will be returned
   *
   * @return {@code true} if and only if the supplied {@code message}
   * is an accepted type as detailed above; {@code false} otherwise
   *
   * @see SimpleChannelInboundHandler#acceptInboundMessage(Object)
   */
  @Override
  public boolean acceptInboundMessage(final Object message) {
    return
      message instanceof HttpRequest ||
      message instanceof HttpContent ||
      message instanceof Http2HeadersFrame ||
      message instanceof Http2DataFrame;
  }

  /**
   * Arranges for the {@link ApplicationHandler} {@linkplain
   * #JerseyChannelInboundHandler(URI, ApplicationHandler)
   * supplied at construction time} to process the HTTP or HTTP/2
   * message represented by the supplied {@link Object}.
   *
   * <p>This method must be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param message the {@link ByteBufHolder}&mdash;an {@link
   * HttpRequest} or a {@link HttpContent}&mdash;to process; must not
   * be {@code null}
   *
   * @exception IllegalArgumentException if {@code message} is not an
   * instance of any of the following types: {@link HttpRequest},
   * {@link Http2HeadersFrame}, {@link HttpContent} or {@link
   * Http2DataFrame}
   *
   * @exception IllegalStateException if any methods of this class
   * have been overridden incorrectly, or in general if any
   * preconditions have been violated
   *
   * @exception Exception if any other error occurs
   *
   * @see #messageReceived(ChannelHandlerContext, ByteBuf, boolean)
   */
  @Override
  protected final void channelRead0(final ChannelHandlerContext channelHandlerContext, final Object message) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    if (message instanceof HttpRequest || message instanceof Http2HeadersFrame) {
      this.messageReceived(channelHandlerContext, message);
    } else if (message instanceof HttpContent || message instanceof Http2DataFrame) {
      this.messageReceived(channelHandlerContext,
                           ((ByteBufHolder)message).content(),
                           message instanceof LastHttpContent || (message instanceof Http2DataFrame) && ((Http2DataFrame)message).isEndStream());
    } else {
      throw new IllegalArgumentException("Unexpected message type: " + message);
    }
  }

  private final void messageReceived(final ChannelHandlerContext channelHandlerContext, final Object requestObject) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    assert channelHandlerContext.executor().inEventLoop();
    assert this.byteBufQueue == null;

    final InjectionManager injectionManager = this.applicationHandler.getInjectionManager();
    if (injectionManager == null) {
      throw new IllegalStateException("applicationHandler.getInjectionManager() == null");
    }
    
    final Supplier<? extends ScheduledExecutorService> supplier =
      () -> injectionManager.getInstance(ScheduledExecutorServiceProvider.class).getExecutorService();
    final ContainerRequest containerRequest;
    final ContainerResponseWriter writer;
    if (requestObject instanceof HttpRequest) {
      final HttpRequest httpRequest = (HttpRequest)requestObject;
      containerRequest = this.createContainerRequest(channelHandlerContext, httpRequest);
      writer = this.createContainerResponseWriter(httpRequest,
                                                  channelHandlerContext,
                                                  supplier);
    } else {
      assert requestObject instanceof Http2HeadersFrame;
      final Http2HeadersFrame http2HeadersFrame = (Http2HeadersFrame)requestObject;
      containerRequest = this.createContainerRequest(channelHandlerContext, http2HeadersFrame);
      writer = this.createContainerResponseWriter(http2HeadersFrame,
                                                  channelHandlerContext,
                                                  supplier);
    }
    if (containerRequest == null) {
      throw new IllegalStateException("createContainerRequest() == null");
    }
    if (writer == null) {
      throw new IllegalStateException("createContainerResponseWriter() == null");
    }
    containerRequest.setWriter(writer);

    injectionManager.getInstance(ExecutorServiceProvider.class).getExecutorService().execute(() -> {
        this.applicationHandler.handle(containerRequest);
      });
  }

  /**
   * Processes the supplied {@link ByteBuf} representing one of
   * possibly many "content" portions of an overall HTTP message.
   *
   * <p>Internally, the supplied {@code content}, if {@linkplain
   * ByteBuf#isReadable() readable}, is {@linkplain ByteBuf#retain()
   * retained} and {@linkplain ByteBufQueue#addByteBuf(ByteBuf) added
   * to an internal <code>ByteBufQueue</code> implementation} created
   * by the {@link #channelRead0(ChannelHandlerContext, Object)}
   * method.
   *
   * <p>This method will be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param content the {@link ByteBuf} to process; must not be {@code
   * null}
   *
   * @param lastOne {@code true} if {@code content} is known to be the
   * last such content chunk; {@code false} in all other cases
   *
   * @exception Exception if an error occurs
   *
   * @see #channelRead0(ChannelHandlerContext, Object)
   */
  private final void messageReceived(final ChannelHandlerContext channelHandlerContext, final ByteBuf content, final boolean lastOne) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    Objects.requireNonNull(content);
    // TODO: in HTTP/2 incoming-payload scenarios this (commented out)
    // refCnt() returnValue can be 4?!  May have to do with all the
    // various handlers up front dealing with upgrades etc.
    //
    // assert content.refCnt() == 1 : "Unexpected refCnt: " + content.refCnt() + "; thread: " + Thread.currentThread();
    assert channelHandlerContext.executor().inEventLoop();

    final ByteBufQueue byteBufQueue = this.byteBufQueue;

    if (content.isReadable()) {
      assert byteBufQueue != null;
      content.retain();
      byteBufQueue.addByteBuf(content);
    }

    if (lastOne && byteBufQueue != null) {
      this.byteBufQueue = null;
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
   * <p>This method will be invoked {@linkplain
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
    return this.createContainerRequest(channelHandlerContext, (Object)httpRequest);
  }

  /**
   * Creates a {@link ContainerRequest} representing the supplied
   * {@link Http2HeadersFrame} and returns it.
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
   * <p>This method will be invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param http2HeadersFrame the {@link Http2HeadersFrame} to
   * process; must not be {@code null}
   *
   * @return a new {@link ContainerRequest}; never {@code null}
   *
   * @see ContainerRequest
   *
   * @see EventLoopPinnedByteBufInputStream
   *
   * @see ByteBufQueue
   */
  protected ContainerRequest createContainerRequest(final ChannelHandlerContext channelHandlerContext, final Http2HeadersFrame http2HeadersFrame) {
    return this.createContainerRequest(channelHandlerContext, (Object)http2HeadersFrame);
  }

  private final ContainerRequest createContainerRequest(final ChannelHandlerContext channelHandlerContext, final Object requestObject) {
    Objects.requireNonNull(channelHandlerContext);
    Objects.requireNonNull(requestObject);
    assert channelHandlerContext.executor().inEventLoop();

    final String method;
    final String uriString;
    final Iterable<? extends CharSequence> nettyHeaderNames;
   if (requestObject instanceof HttpRequest) {
      final HttpRequest httpRequest = (HttpRequest)requestObject;
      final HttpHeaders httpHeaders = httpRequest.headers();
      assert httpHeaders != null;
      nettyHeaderNames = httpHeaders.names();
      method = httpRequest.method().name();
      uriString = httpRequest.uri();
    } else {
      assert requestObject instanceof Http2HeadersFrame;
      final Http2HeadersFrame http2HeadersFrame = (Http2HeadersFrame)requestObject;
      final Http2Headers headers = http2HeadersFrame.headers();
      nettyHeaderNames = headers.names();
      method = headers.method().toString();
      uriString = headers.path().toString();
    }
    assert method != null;
    assert uriString != null;

    final SecurityContext securityContext = this.createSecurityContext(channelHandlerContext, requestObject);
    
    final ContainerRequest returnValue =
      new ContainerRequest(this.baseUri,
                           baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(uriString.startsWith("/") && uriString.length() > 1 ? uriString.substring(1) : uriString)),
                           method,
                           securityContext == null ? new SecurityContextAdapter() : securityContext,
                           new MapBackedPropertiesDelegate());

    final BiConsumer<? super ContainerRequest, ? super CharSequence> headersInstaller;
    if (requestObject instanceof HttpRequest) {
      headersInstaller = (containerRequest, name) -> containerRequest.headers(name.toString(), ((HttpRequest)requestObject).headers().getAll(name));
    } else {
      assert requestObject instanceof Http2HeadersFrame;
      headersInstaller = (containerRequest, name) -> containerRequest.headers(name.toString(), (Iterable<CharSequence>)() -> ((Http2HeadersFrame)requestObject).headers().valueIterator(name));
    }
    
    copyHeaders(nettyHeaderNames, returnValue, headersInstaller);

    if (needsInputStream(requestObject)) {
      final EventLoopPinnedByteBufInputStream entityStream =
        new EventLoopPinnedByteBufInputStream(channelHandlerContext.alloc(),
                                              channelHandlerContext.executor());
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
  private final SecurityContext createSecurityContext(final ChannelHandlerContext channelHandlerContext, final Object httpRequest) {
    return new SecurityContextAdapter();
  }
  
  /**
   * Creates and returns a new {@link ContainerResponseWriter} when
   * invoked.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>This implementation simply invokes the {@link
   * HttpContainerResponseWriter#HttpContainerResponseWriter(HttpRequest,
   * ChannelHandlerContext, Supplier)} constructor and returns the new
   * object.</p>
   *
   * <p>In normal usage, this method is invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * <p>This method is called by the {@link
   * #createContainerRequest(ChannelHandlerContext, HttpRequest)} method.
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
   * @return a new {@link ContainerResponseWriter}; never {@code null}
   *
   * @see
   * HttpContainerResponseWriter#HttpContainerResponseWriter(HttpRequest,
   * ChannelHandlerContext, Supplier)
   *
   * @see #createContainerRequest(ChannelHandlerContext, HttpRequest)
   */
  protected ContainerResponseWriter createContainerResponseWriter(final HttpRequest httpRequest,
                                                                  final ChannelHandlerContext channelHandlerContext,
                                                                  final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    return new HttpContainerResponseWriter(httpRequest, channelHandlerContext, scheduledExecutorServiceSupplier);
  }

  /**
   * Creates and returns a new {@link ContainerResponseWriter} when
   * invoked.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>This implementation simply invokes the {@link
   * Http2ContainerResponseWriter#Http2ContainerResponseWriter(Http2HeadersFrame,
   * ChannelHandlerContext, Supplier)} constructor and returns the new
   * object.</p>
   *
   * <p>In normal usage, this method is invoked {@linkplain
   * EventExecutor#inEventLoop() in the Netty event loop}.</p>
   *
   * <p>This method is called by the {@link
   * #createContainerRequest(ChannelHandlerContext,
   * Http2HeadersFrame)} method.  Overrides must not call that method
   * or an infinite loop may result.</p>
   *
   * @param http2HeadersFrame the {@link Http2HeadersFrame} being
   * processed; must not be {@code null}
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param scheduledExecutorServiceSupplier a {@link Supplier} that
   * can {@linkplain Supplier#get() supply} a {@link
   * ScheduledExecutorService}; must not be {@code null}
   *
   * @return a new {@link ContainerResponseWriter}; never {@code null}
   *
   * @see
   * Http2ContainerResponseWriter#Http2ContainerResponseWriter(Http2HeadersFrame,
   * ChannelHandlerContext, Supplier)
   *
   * @see #createContainerRequest(ChannelHandlerContext, HttpRequest)
   */
  protected ContainerResponseWriter createContainerResponseWriter(final Http2HeadersFrame http2HeadersFrame,
                                                                  final ChannelHandlerContext channelHandlerContext,
                                                                  final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    return new Http2ContainerResponseWriter(http2HeadersFrame, channelHandlerContext, scheduledExecutorServiceSupplier);
  }


  /*
   * Static utility methods.
   */
  
  
  private static final boolean needsInputStream(final Object requestObject) {
    final boolean returnValue;
    if (requestObject instanceof HttpRequest) {
      final HttpRequest httpRequest = (HttpRequest)requestObject;
      returnValue = HttpUtil.getContentLength(httpRequest, -1L) > 0L || HttpUtil.isTransferEncodingChunked(httpRequest);
    } else {
      assert requestObject instanceof Http2HeadersFrame;
      returnValue = !((Http2HeadersFrame)requestObject).isEndStream();
    }
    return returnValue;
  }

  /**
   * A utility method that installs a collection of Netty-sourced HTTP
   * or HTTP/2 headers into the supplied (Jersey) {@link
   * ContainerRequest}, using the supplied {@link BiConsumer} as a
   * strategy.
   *
   * @param nettyHeaderNames an {@link Iterable} of {@link
   * CharSequence}-typed header names; may be {@code null} in which
   * case no action will be taken
   *
   * @param target the {@link ContainerRequest} into which the headers
   * should be installed; must not be {@code null} if {@code
   * nettyHeaderNames} is non-{@code null}
   *
   * @param headersInstaller a {@link BiConsumer} that accepts the
   * supplied {@link ContainerRequest} and a {@link CharSequence}
   * representing the current header name whose values should be
   * installed; must not be {@code null} if {@code nettyHeaderNames}
   * is non-{@code null}
   *
   * @exception NullPointerException if {@code target} or {@code
   * headersInstaller} is {@code null} and {@code nettyHeaderNames} is
   * non-{@code null}
   */
  public static final void copyHeaders(final Iterable<? extends CharSequence> nettyHeaderNames,
                                       final ContainerRequest target,
                                       final BiConsumer<? super ContainerRequest, ? super CharSequence> headersInstaller) {
    if (nettyHeaderNames != null) {
      Objects.requireNonNull(target);
      Objects.requireNonNull(headersInstaller);
      for (final CharSequence headerName : nettyHeaderNames) {
        headersInstaller.accept(target, headerName);
      }
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
