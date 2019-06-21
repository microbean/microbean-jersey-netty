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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;

import io.netty.handler.stream.ChunkedInput;

import io.netty.util.ReferenceCounted;

import io.netty.util.concurrent.EventExecutor;

import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

import org.glassfish.jersey.spi.ScheduledExecutorServiceProvider;

/**
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see ContainerResponseWriter
 */
public class Http11NettyContainerResponseWriter extends AbstractNettyContainerResponseWriter<HttpRequest> {


  /*
   * Instance fields.
   */


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link Http11NettyContainerResponseWriter}.
   *
   * @param httpRequest the {@link HttpRequest} being responded to;
   * must not be {@code null}
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext}
   * representing the current Netty execution; must not be {@code
   * null}
   *
   * @param scheduledExecutorServiceSupplier a {@link Supplier} that
   * can {@linkplain Supplier#get() supply} a {@link
   * ScheduledExecutorService}; must not be {@code null}
   *
   * @exception NullPointerException if any of the parameters is
   * {@code null}
   */
  public Http11NettyContainerResponseWriter(final HttpRequest httpRequest,
                                            final ChannelHandlerContext channelHandlerContext,
                                            final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    super(httpRequest, channelHandlerContext, scheduledExecutorServiceSupplier);
  }


  /*
   * Instance methods.
   */
  
  
  @Override
  protected final void writeAndFlushStatusAndHeaders(final ContainerResponse containerResponse,
                                                     final long contentLength) {
    Objects.requireNonNull(containerResponse);

    final String reasonPhrase = containerResponse.getStatusInfo().getReasonPhrase();
    final HttpResponseStatus status = reasonPhrase == null ? HttpResponseStatus.valueOf(containerResponse.getStatus()) : new HttpResponseStatus(containerResponse.getStatus(), reasonPhrase);

    final HttpResponse httpResponse;
    if (contentLength < 0L) {
      httpResponse = new DefaultHttpResponse(this.requestObject.protocolVersion(), status);
      HttpUtil.setTransferEncodingChunked(httpResponse, true);
    } else if (contentLength == 0L) {
      httpResponse = new DefaultFullHttpResponse(this.requestObject.protocolVersion(), status);
      HttpUtil.setContentLength(httpResponse, 0L);
    } else {
      httpResponse = new DefaultHttpResponse(this.requestObject.protocolVersion(), status);
      HttpUtil.setContentLength(httpResponse, contentLength);
    }

    final HttpHeaders headers = httpResponse.headers();
    assert headers != null;
    transferHeaders(containerResponse.getStringHeaders(), UnaryOperator.identity(), headers::add);
    if (HttpUtil.isKeepAlive(this.requestObject)) {
      HttpUtil.setKeepAlive(httpResponse, true);
    }
    this.channelHandlerContext.writeAndFlush(httpResponse);
  }

  @Override
  protected final boolean needsOutputStream(final long contentLength) {
    return contentLength != 0 && !HttpMethod.HEAD.equals(this.requestObject.method());
  }
  
  /**
   * Creates and returns a new {@link ChunkedInput} instance whose
   * {@link ChunkedInput#readChunk(ByteBufAllocator)} method will
   * stream {@link ByteBuf} chunks to the Netty transport.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>In normal usage this method will be invoked on a thread that
   * is <strong>not</strong> {@linkplain EventExecutor#inEventLoop()
   * in the Netty event loop}.  Care <strong>must</strong> be taken if
   * an override of this method decides to invoke any methods on the
   * supplied {@link ByteBuf} to ensure those methods are invoked in
   * the Netty event loop.  This implementation does not invoke any
   * {@link ByteBuf} methods and overrides are strongly urged to
   * follow suit.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * <p>This method is called from the {@link
   * #writeResponseStatusAndHeaders(long, ContainerResponse)} method.
   * Overrides must not call that method or an infinite loop may
   * result.</p>
   *
   * @param eventExecutor an {@link EventExecutor}, supplied for
   * convenience, that can be used to ensure certain tasks are
   * executed in the Netty event loop; must not be {@code null}
   *
   * @param source the {@link ByteBuf}, <strong>which might be
   * mutating</strong> in the Netty event loop, that the returned
   * {@link ChunkedInput} should read from in some way when its {@link
   * ChunkedInput#readChunk(ByteBufAllocator)} method is called from
   * the Netty event loop; must not be {@code null}
   *
   * @param contentLength a {@code long} representing the value of any
   * {@code Content-Length} header that might have been present; it is
   * guaranteed that when this method is invoked by the default
   * implementation of the {@link #writeResponseStatusAndHeaders(long,
   * ContainerResponse)} method this parameter will never be {@code
   * 0L} but might very well be less than {@code 0L} to indicate an
   * unknown content length
   *
   * @return a new {@link ChunkedInput} that reads in some way from
   * the supplied {@code source} when its {@link
   * ChunkedInput#readChunk(ByteBufAllocator)} method is called from
   * the Netty event loop; never {@code null}
   *
   * @see ByteBufChunkedInput#ByteBufChunkedInput(ByteBuf, long)
   *
   * @see ChunkedInput#readChunk(ByteBufAllocator)
   */
  @Override
  protected final ChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor, final ByteBuf source, final long contentLength) {
    return new ByteBufChunkedInput(source, contentLength);
  }

  @Override
  protected final void writeLastContentMessage() {
    // Send the magic message that tells the HTTP machinery to
    // finish up.
    this.channelHandlerContext.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
  }

  @Override
  protected final void writeFailureMessage() {
    final HttpMessage failureMessage =
      new DefaultFullHttpResponse(this.requestObject.protocolVersion(),
                                  HttpResponseStatus.INTERNAL_SERVER_ERROR);
    HttpUtil.setContentLength(failureMessage, 0L);
    this.channelHandlerContext.write(failureMessage);
  }


}
