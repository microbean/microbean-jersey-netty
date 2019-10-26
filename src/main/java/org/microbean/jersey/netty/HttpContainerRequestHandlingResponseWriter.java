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
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;

public final class HttpContainerRequestHandlingResponseWriter extends AbstractContainerRequestHandlingResponseWriter {

  private static final String cn = HttpContainerRequestHandlingResponseWriter.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);

  private static final GenericFutureListener<? extends Future<? super Void>> listener = f -> {
    final Throwable cause = f.cause();
    if (cause != null && logger.isLoggable(Level.SEVERE)) {
      logger.log(Level.SEVERE, cause.getMessage(), cause);
    }    
  };

  private HttpVersion httpVersion;
  
  public HttpContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    super(applicationHandler);
  }

  @Override
  protected final boolean writeStatusAndHeaders(final long contentLength,
                                                final ContainerResponse containerResponse) {
    Objects.requireNonNull(containerResponse);
    
    final ContainerRequest containerRequest = containerResponse.getRequestContext();
    if (containerRequest == null) {
      throw new IllegalArgumentException("containerResponse.getRequestContext() == null");
    }
    
    final Object httpRequestValue = containerRequest.getProperty("org.microbean.jersey.netty.HttpRequest");
    if (!(httpRequestValue instanceof HttpRequest)) {
      throw new IllegalArgumentException("!(containerResponse.getRequestContext().getProperty(\"org.microbean.jersey.netty.HttpRequest\") instanceof HttpRequest): " + httpRequestValue);
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

  @Override
  protected OutputStream createOutputStream(final long contentLength,
                                            final ContainerResponse containerResponse) {
    Objects.requireNonNull(containerResponse);
    if (contentLength == 0L) {
      throw new IllegalArgumentException("contentLength == 0L");
    }
    final OutputStream returnValue = new ByteBufBackedChannelOutboundInvokingHttpContentOutputStream(this.getChannelHandlerContext(), 8192, false, null);
    return returnValue;
  }

  
  @Override
  public final void commit() {
    this.httpVersion = null;
  }

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
