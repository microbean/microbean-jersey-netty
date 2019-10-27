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

import java.util.Objects;

import java.util.concurrent.ScheduledExecutorService;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import io.netty.handler.stream.ChunkedInput;

import io.netty.util.concurrent.EventExecutor;

import org.glassfish.jersey.server.ContainerResponse;

/**
 * An {@link AbstractNettyContainerResponseWriter} that works with
 * {@link HttpRequest}-typed request objects.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see AbstractNettyContainerResponseWriter
 *
 * @see Http2ContainerResponseWriter
 *
 * @deprecated Slated for removal.
 */
@Deprecated
public class HttpContainerResponseWriter extends AbstractNettyContainerResponseWriter<HttpRequest> {



  /*
   * Static fields.
   */


  private static final String cn = HttpContainerResponseWriter.class.getName();

  private static final Logger logger = Logger.getLogger(cn);


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link HttpContainerResponseWriter}.
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
  public HttpContainerResponseWriter(final HttpRequest httpRequest,
                                     final ChannelHandlerContext channelHandlerContext,
                                     final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    super(httpRequest, channelHandlerContext, scheduledExecutorServiceSupplier);
  }


  /*
   * Instance methods.
   */


  /**
   * Implements the {@link
   * AbstractNettyContainerResponseWriter#writeAndFlushStatusAndHeaders(ContainerResponse,
   * long, ChannelPromise)} method by {@linkplain
   * ChannelHandlerContext#writeAndFlush(Object) writing and flushing}
   * an {@link HttpResponse} object containing a relevant {@link
   * HttpResponseStatus} object.
   *
   * @param containerResponse the {@link ContainerResponse} being
   * processed; must not be {@code null}
   *
   * @param contentLength the length of the content in bytes; will be
   * less than {@code 0} if the content length is unknown
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code containerResponse} or
   * {@code channelPromise} is {@code null}
   *
   * @see #writeResponseStatusAndHeaders(long, ContainerResponse)
   */
  @Override
  protected final void writeAndFlushStatusAndHeaders(final ContainerResponse containerResponse,
                                                     final long contentLength,
                                                     final ChannelPromise channelPromise) {
    final String mn = "writeAndFlushStatusAndHeaders";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn, new Object[] { containerResponse, Long.valueOf(contentLength) });
    }
    Objects.requireNonNull(containerResponse);
    Objects.requireNonNull(channelPromise);

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

    final HttpHeaders nettyHeaders = httpResponse.headers();
    assert nettyHeaders != null;
    copyHeaders(containerResponse.getStringHeaders(), UnaryOperator.identity(), nettyHeaders::add);
    if (HttpUtil.isKeepAlive(this.requestObject)) {
      HttpUtil.setKeepAlive(httpResponse, true);
    }
    this.channelHandlerContext.writeAndFlush(httpResponse, channelPromise);

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn);
    }
  }

  /**
   * Implements the {@link
   * AbstractNettyContainerResponseWriter#needsOutputStream(long)}
   * method by returning {@code true} if and only if the supplied
   * {@code contentLength} is not equal to {@code 0L} and if the
   * {@linkplain HttpRequest#method() request method} is not {@link
   * HttpMethod#HEAD HEAD}.
   *
   * @param contentLength the length of the content in bytes; will be
   * less than {@code 0} if the content length is unknown
   *
   * @return {@code true} if {@code contentLength} is not equal to
   * {@code 0L} and if the {@linkplain HttpRequest#method() request
   * method} is not {@link HttpMethod#HEAD HEAD}; {@code false} in all
   * other cases
   */
  @Override
  protected final boolean needsOutputStream(final long contentLength) {
    return contentLength != 0L && !HttpMethod.HEAD.equals(this.requestObject.method());
  }

  /**
   * Returns a new {@link FunctionalByteBufChunkedInput
   * FunctionalByteBufChunkedInput&lt;ByteBuf&gt;} when invoked.
   *
   * @param eventExecutor {@inheritDoc}
   *
   * @param source {@inheritDoc}
   *
   * @param contentLength {@inheritDoc}
   *
   * @return a new {@link BoundedChunkedInput}; never {@code null}
   *
   * @exception NullPointerException if {@code eventExecutor} or
   * {@code source} is {@code null}
   *
   * @see AbstractNettyContainerResponseWriter#createChunkedInput(EventExecutor, ByteBuf, long)
   *
   * @see
   * FunctionalByteBufChunkedInput#FunctionalByteBufChunkedInput(ByteBuf,
   * Function, long)
   *
   * @see ChunkedInput#readChunk(ByteBufAllocator)
   */
  @Override
  protected final BoundedChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor, final ByteBuf source, final long contentLength) {
    final String mn = "createChunkedInput";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn, new Object[] { eventExecutor, source, Long.valueOf(contentLength) });
    }
    final BoundedChunkedInput<?> returnValue = new FunctionalByteBufChunkedInput<ByteBuf>(source, UnaryOperator.identity(), contentLength);
    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn, returnValue);
    }
    return returnValue;
  }

  /**
   * {@linkplain ChannelHandlerContext#write(Object) Writes} {@link
   * LastHttpContent#EMPTY_LAST_CONTENT} when invoked.
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelPromise} is
   * {@code null}
   *
   * @see LastHttpContent
   *
   * @see AbstractNettyContainerResponseWriter#writeLastContentMessage(ChannelPromise)
   */
  @Override
  protected final void writeLastContentMessage(final ChannelPromise channelPromise) {
    Objects.requireNonNull(channelPromise);
    // Send the magic message that tells the HTTP machinery to
    // finish up.
    this.channelHandlerContext.write(LastHttpContent.EMPTY_LAST_CONTENT, channelPromise);
  }

  /**
   * {@linkplain ChannelHandlerContext#write(Object) Writes} a new
   * {@link DefaultFullHttpResponse} message with a {@code
   * Content-Length} of {@code 0} and a status equal to {@link
   * HttpResponseStatus#INTERNAL_SERVER_ERROR}.
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelPromise} is
   * {@code null}
   *
   * @see
   * AbstractNettyContainerResponseWriter#writeFailureMessage(ChannelPromise)
   */
  @Override
  protected final void writeFailureMessage(final ChannelPromise channelPromise) {
    Objects.requireNonNull(channelPromise);
    final HttpMessage failureMessage = new DefaultFullHttpResponse(this.requestObject.protocolVersion(),
                                                                   HttpResponseStatus.INTERNAL_SERVER_ERROR);
    HttpUtil.setContentLength(failureMessage, 0L);
    this.channelHandlerContext.write(failureMessage, channelPromise);
  }

}
