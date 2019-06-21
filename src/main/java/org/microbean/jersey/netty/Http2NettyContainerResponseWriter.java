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

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelHandlerContext;

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

public class Http2NettyContainerResponseWriter extends AbstractNettyContainerResponseWriter<Http2HeadersFrame> {

  public Http2NettyContainerResponseWriter(final Http2HeadersFrame http2HeadersFrame,
                                           final ChannelHandlerContext channelHandlerContext,
                                           final Supplier<? extends ScheduledExecutorService> scheduledExecutorServiceSupplier) {
    super(http2HeadersFrame, channelHandlerContext, scheduledExecutorServiceSupplier);
  }

  @Override
  protected final void writeAndFlushStatusAndHeaders(final ContainerResponse containerResponse,
                                                     final long contentLength) {
    Objects.requireNonNull(containerResponse);

    final Http2Headers http2Headers = new DefaultHttp2Headers();
    http2Headers.status(Integer.toString(containerResponse.getStatus()));
    transferHeaders(containerResponse.getStringHeaders(), s -> s.toLowerCase(), http2Headers::add);
    if (contentLength >= 0L) {
      // CONTENT_LENGTH is a constant that is guaranteed to be in
      // lowercase so we aren't inconsistent.
      http2Headers.set(HttpHeaderNames.CONTENT_LENGTH, Long.toString(contentLength));
    }
    this.channelHandlerContext.writeAndFlush(new DefaultHttp2HeadersFrame(http2Headers, contentLength == 0L));
  }

  @Override
  protected final ChunkedInput<?> createChunkedInput(final EventExecutor eventExecutor, final ByteBuf source, final long contentLength) {
    return new FunctionalByteBufChunkedInput<Http2DataFrame>(source, bb -> new DefaultHttp2DataFrame(bb), contentLength);
  }

  @Override
  protected final void writeLastContentMessage() {
    this.channelHandlerContext.write(new DefaultHttp2DataFrame(true));
  }
  
  @Override
  protected final void writeFailureMessage() {
    this.channelHandlerContext.write(new DefaultHttp2Headers().status(HttpResponseStatus.INTERNAL_SERVER_ERROR.codeAsText()));
  }

  @Override
  protected final boolean needsOutputStream(final long contentLength) {
    return contentLength != 0L && !HttpMethod.HEAD.equals(HttpMethod.valueOf(this.requestObject.headers().method().toString().toUpperCase()));

  }
  

}
