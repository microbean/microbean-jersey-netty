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

import java.io.OutputStream;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.function.UnaryOperator;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.HttpMethod;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse; // for javadoc only
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter; // for javadoc only

import org.microbean.jersey.netty.AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator;

/**
 * An {@link AbstractContainerRequestHandlingResponseWriter}
 * implemented in terms of {@link HttpRequest}s, {@link HttpResponse}s
 * and {@link
 * ByteBufBackedChannelOutboundInvokingHttpContentOutputStream}s.
 *
 * @author <a href="https://about.me/lairdnelson" target="_parent">Laird Nelson</a>
 *
 * @see AbstractContainerRequestHandlingResponseWriter
 *
 * @see ByteBufBackedChannelOutboundInvokingHttpContentOutputStream
 *
 * @see #writeStatusAndHeaders(long, ContainerResponse)
 *
 * @see #createOutputStream(long, ContainerResponse)
 */
public final class HttpContainerRequestHandlingResponseWriter extends AbstractContainerRequestHandlingResponseWriter<HttpContent> {


  /*
   * Static fields.
   */
  
  
  private static final String cn = HttpContainerRequestHandlingResponseWriter.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);

  private static final GenericFutureListener<? extends Future<? super Void>> listener = f -> {
    final Throwable cause = f.cause();
    if (cause != null && logger.isLoggable(Level.SEVERE)) {
      logger.log(Level.SEVERE, cause.getMessage(), cause);
    }    
  };


  /*
   * Instance fields.
   */
  

  /**
   * The {@link HttpVersion} currently in effect.
   *
   * <p>The only reason this field has to exist is so that the {@link
   * #writeFailureMessage(Throwable)} method knows what HTTP version
   * to use (1.0 or 1.1).</p>
   *
   * @see #writeFailureMessage(Throwable)
   */
  private HttpVersion httpVersion;


  /*
   * Constructors.
   */

  
  /**
   * Creates a new {@link HttpContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  public HttpContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    super(applicationHandler);
  }

  /**
   * Creates a new {@link HttpContainerRequestHandlingResponseWriter}.
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
   * OutputStream} returned by the {@link #createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain OutputStream#flush() flush} may take place; if less
   * than {@code 0} {@code 0} will be used instead; if {@link
   * Integer#MAX_VALUE} then it is suggested that no automatic
   * flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be used
   * by the {@link #createOutputStream(long, ContainerResponse)}
   * method; may be {@code null}
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  public HttpContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler,
                                                    final int flushThreshold,
                                                    final ByteBufCreator byteBufCreator) {
    super(applicationHandler, flushThreshold, byteBufCreator);
  }


  /*
   * Instance methods.
   */
  

  /**
   * Writes the status and headers portion of the response present in
   * the supplied {@link ContainerResponse} and returns {@code true}
   * if further output is forthcoming.
   *
   * <p>This implementation writes an instance of either {@link
   * DefaultHttpResponse} or {@link DefaultFullHttpResponse}.</p>
   *
   * @param contentLength the content length as determined by the
   * logic encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method; a value less
   * than zero indicates an unknown content length
   *
   * @param containerResponse the {@link ContainerResponse} containing
   * status and headers information; must not be {@code null}
   *
   * @return {@code true} if the {@link #createOutputStream(long,
   * ContainerResponse)} method should be invoked, <em>i.e.</em> if
   * further output is forthcoming
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null}
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see #createOutputStream(long, ContainerResponse) 
   */  
  @Override
  protected final boolean writeStatusAndHeaders(final long contentLength,
                                                final ContainerResponse containerResponse) {
    Objects.requireNonNull(containerResponse);
    
    final ContainerRequest containerRequest = containerResponse.getRequestContext();
    if (containerRequest == null) {
      throw new IllegalArgumentException("containerResponse.getRequestContext() == null");
    }
    
    final Object httpRequestValue = containerRequest.getProperty(HttpRequest.class.getName());
    if (!(httpRequestValue instanceof HttpRequest)) {
      throw new IllegalArgumentException("containerResponse; !(containerResponse.getRequestContext().getProperty(\"" +
                                         HttpRequest.class.getName() +
                                         "\") instanceof HttpRequest): " + httpRequestValue);
    }
    final HttpRequest httpRequest = (HttpRequest)httpRequestValue;
    
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());

    final HttpVersion httpVersion = httpRequest.protocolVersion();
    assert httpVersion != null;
    this.httpVersion = httpVersion;

    final HttpResponseStatus status;
    final String reasonPhrase = containerResponse.getStatusInfo().getReasonPhrase();
    if (reasonPhrase == null) {
      status = HttpResponseStatus.valueOf(containerResponse.getStatus());
    } else {
      status = HttpResponseStatus.valueOf(containerResponse.getStatus(), reasonPhrase);
    }

    final HttpMessage httpResponse;
    final boolean needsOutputStream;
    if (contentLength < 0L) {
      needsOutputStream = !HttpMethod.HEAD.equalsIgnoreCase(containerRequest.getMethod());
      httpResponse = new DefaultHttpResponse(httpVersion, status);
      copyHeaders(containerResponse.getStringHeaders(), httpResponse.headers());
      HttpUtil.setTransferEncodingChunked(httpResponse, true);
    } else if (contentLength == 0L) {
      needsOutputStream = false;
      httpResponse = new DefaultFullHttpResponse(httpVersion, status);
      copyHeaders(containerResponse.getStringHeaders(), httpResponse.headers());
      HttpUtil.setContentLength(httpResponse, 0L);
    } else {
      needsOutputStream = !HttpMethod.HEAD.equalsIgnoreCase(containerRequest.getMethod());
      httpResponse = new DefaultHttpResponse(httpVersion, status);
      copyHeaders(containerResponse.getStringHeaders(), httpResponse.headers());
      HttpUtil.setContentLength(httpResponse, contentLength);
    }
    if (HttpUtil.isKeepAlive(httpRequest)) {
      HttpUtil.setKeepAlive(httpResponse, true);
    }
    
    final ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    channelPromise.addListener(listener);
    
    if (needsOutputStream) {
      channelHandlerContext.write(httpResponse, channelPromise);
    } else {
      channelHandlerContext.writeAndFlush(httpResponse, channelPromise);
    }

    return needsOutputStream;
  }

  /**
   * Creates and returns a new {@link
   * ByteBufBackedChannelOutboundInvokingHttpContentOutputStream}.
   *
   * @param contentLength the content length as determined by the
   * logic encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method; must not be
   * {@code 0L}
   *
   * @param containerResponse a {@link ContainerResponse} for which an
   * {@link OutputStream} is being created and returned; must not be
   * {@code null}; ignored by this implementation
   *
   * @return a new {@link
   * ByteBufBackedChannelOutboundInvokingHttpContentOutputStream}
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null} or if {@link #getChannelHandlerContext()} returns
   * {@code null}
   *
   * @exception IllegalArgumentException if {@code contentLength} is
   * {@code 0L}
   *
   * @see ByteBufBackedChannelOutboundInvokingHttpContentOutputStream
   */
  @Override
  protected AbstractChannelOutboundInvokingOutputStream<? extends HttpContent> createOutputStream(final long contentLength,
                                                                                                  final ContainerResponse containerResponse) {
    Objects.requireNonNull(containerResponse);
    if (contentLength == 0L) {
      throw new IllegalArgumentException("contentLength == 0L");
    }
    final AbstractChannelOutboundInvokingOutputStream<? extends HttpContent> returnValue =
      new ByteBufBackedChannelOutboundInvokingHttpContentOutputStream(this.getChannelHandlerContext(),
                                                                      this.getFlushThreshold(),
                                                                      false,
                                                                      this.getByteBufCreator());
    return returnValue;
  }

  /**
   * Overrides {@link ContainerResponseWriter#commit()} to effectively
   * do nothing when invoked since the {@link OutputStream} returned
   * by {@link #createOutputStream(long, ContainerResponse)} handles
   * committing the response.
   *
   * @see #createOutputStream(long, ContainerResponse)
   */
  @Override
  public final void commit() {
    this.httpVersion = null;
    super.commit();
  }

  /**
   * Writes an appropriate failure message using the return value of
   * the {@link #getChannelHandlerContext()} method.
   *
   * @param failureCause the {@link Throwable} responsible for this
   * method's invocation; may be {@code null} in pathological cases;
   * ignored by this implementation
   *
   * @exception NullPointerException if {@link
   * #getChannelHandlerContext()} returns {@code null}
   */
  @Override
  protected final void writeFailureMessage(final Throwable failureCause) {
    try {
      final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
      final ChannelPromise channelPromise = channelHandlerContext.newPromise();
      assert channelPromise != null;
      channelPromise.addListener(listener);
      final HttpMessage failureMessage = new DefaultFullHttpResponse(this.httpVersion, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      HttpUtil.setContentLength(failureMessage, 0L);
      channelHandlerContext.write(failureMessage, channelPromise);
    } finally {
      this.httpVersion = null;
    }
  }

  private static final void copyHeaders(final Map<? extends String, ? extends List<String>> headersSource,
                                        final HttpHeaders nettyHeaders) {    
    AbstractContainerRequestHandlingResponseWriter.copyHeaders(headersSource, UnaryOperator.identity(), nettyHeaders::add);
  }
  
}
