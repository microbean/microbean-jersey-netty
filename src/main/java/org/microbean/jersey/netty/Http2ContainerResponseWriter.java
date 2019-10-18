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

import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;

import io.netty.handler.stream.ChunkedInput;

import io.netty.util.concurrent.EventExecutor;

import org.glassfish.jersey.server.ContainerResponse;

/**
 * An {@link AbstractNettyContainerResponseWriter} that works with
 * {@link Http2HeadersFrame}-typed request objects.
 *
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see AbstractNettyContainerResponseWriter
 *
 * @see HttpContainerResponseWriter
 */
public class Http2ContainerResponseWriter extends AbstractNettyContainerResponseWriter<Http2HeadersFrame> {


  /*
   * Static fields.
   */


  private static final String cn = Http2ContainerResponseWriter.class.getName();

  private static final Logger logger = Logger.getLogger(cn);


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link Http2ContainerResponseWriter}.
   *
   * @param http2HeadersFrame the {@link Http2HeadersFrame} being
   * responded to; must not be {@code null}
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
  public Http2ContainerResponseWriter(final Http2HeadersFrame http2HeadersFrame,
                                      final ChannelHandlerContext channelHandlerContext,
                                      final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    super(http2HeadersFrame, channelHandlerContext, scheduledExecutorServiceSupplier);
  }


  /*
   * Instance methods.
   */


  /**
   * Implements the {@link
   * AbstractNettyContainerResponseWriter#writeAndFlushStatusAndHeaders(ContainerResponse,
   * long, ChannelPromise)} method by {@linkplain
   * ChannelHandlerContext#writeAndFlush(Object) writing and flushing}
   * an {@link DefaultHttp2HeadersFrame} object.
   *
   * @param containerResponse the {@link ContainerResponse} being
   * processed; must not be {@code null}
   *
   * @param contentLength the length of the content in bytes; will be
   * less than {@code 0} if the content length is unknown
   *
   * @exception NullPointerException if {@code containerResponse} or
   * {@code channelPromise} is {@code null}
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

    final Http2Headers nettyHeaders = new DefaultHttp2Headers();
    nettyHeaders.status(Integer.toString(containerResponse.getStatus()));
    copyHeaders(containerResponse.getStringHeaders(), String::toLowerCase, nettyHeaders::add);
    if (contentLength >= 0L) {
      // CONTENT_LENGTH is a constant that is guaranteed to be in
      // lowercase so we aren't inconsistent.
      nettyHeaders.set(HttpHeaderNames.CONTENT_LENGTH, Long.toString(contentLength));
    }
    this.channelHandlerContext.writeAndFlush(new DefaultHttp2HeadersFrame(nettyHeaders, contentLength == 0L), channelPromise);

    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn);
    }
  }

  /**
   * Implements the {@link
   * AbstractNettyContainerResponseWriter#needsOutputStream(long)}
   * method by returning {@code true} if and only if the supplied
   * {@code contentLength} is not equal to {@code 0L} and if the
   * {@linkplain Http2Headers#method() request method} {@linkplain
   * String#toUpperCase() converted to uppercase} is not equal to the
   * return value of invoking {@link HttpMethod#name()
   * HttpMethod.HEAD.name()}.
   *
   * @param contentLength the length of the content in bytes; will be
   * less than {@code 0} if the content length is unknown
   *
   * @return {@code true} if {@code contentLength} is not equal to
   * {@code 0L} and if the {@linkplain Http2Headers#method() request
   * method} {@linkplain String#toUpperCase() converted to uppercase}
   * is not equal to the return value of invoking {@link
   * HttpMethod#name() HttpMethod.HEAD.name()}; {@code false} in all
   * other cases
   */
  @Override
  protected final boolean needsOutputStream(final long contentLength) {
    return contentLength != 0L && !HttpMethod.HEAD.equals(HttpMethod.valueOf(this.requestObject.headers().method().toString().toUpperCase()));
  }

  /**
   * Returns a new {@link FunctionalByteBufChunkedInput
   * FunctionalByteBufChunkedInput&lt;Http2DataFrame&gt;} when invoked.
   *
   * @param eventExecutor {@inheritDoc}
   *
   * @param source {@inheritDoc}
   *
   * @param contentLength {@inheritDoc}
   *
   * @return a new {@link FunctionalByteBufChunkedInput
   * FunctionalByteBufChunkedInput&lt;Http2DataFrame&gt;}; never
   * {@code null}
   *
   * @see AbstractNettyContainerResponseWriter#createChunkedInput(EventExecutor, ByteBuf, long)
   *
   * @see ChunkedInput#readChunk(ByteBufAllocator)
   */
  @Override
  protected final BoundedChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor, final ByteBuf source, final long contentLength) {
    final String mn = "createChunkedInput";
    if (logger.isLoggable(Level.FINER)) {
      logger.entering(cn, mn, new Object[] { eventExecutor, source, Long.valueOf(contentLength) });
    }
    final BoundedChunkedInput<?> returnValue =  new FunctionalByteBufChunkedInput<Http2DataFrame>(source, DefaultHttp2DataFrame::new, contentLength);
    if (logger.isLoggable(Level.FINER)) {
      logger.exiting(cn, mn, returnValue);
    }
    return returnValue;
  }

  /**
   * {@linkplain ChannelHandlerContext#write(Object) Writes} and
   * implicitly flushes {@link
   * DefaultHttp2DataFrame#DefaultHttp2DataFrame(boolean) new
   * DefaultHttp2DataFrame(true)} when invoked.
   *
   * @param channelPromise a {@link ChannelPromise} to pass to any
   * write operation; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelPromise} is
   * {@code null}
   *
   * @see DefaultHttp2DataFrame#DefaultHttp2DataFrame(boolean)
   *
   * @see
   * AbstractNettyContainerResponseWriter#writeLastContentMessage(ChannelPromise)
   */
  @Override
  protected final void writeLastContentMessage(final ChannelPromise channelPromise) {
    Objects.requireNonNull(channelPromise);
    this.channelHandlerContext.write(new DefaultHttp2DataFrame(true), channelPromise);
  }

  /**
   * {@linkplain ChannelHandlerContext#write(Object) Writes} a new
   * {@link DefaultHttp2Headers} message with a {@linkplain
   * Http2Headers#status(CharSequence) status} equal to {@code 500}.
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
    this.channelHandlerContext.write(new DefaultHttp2Headers().status(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText()),
                                     channelPromise);
  }

}
