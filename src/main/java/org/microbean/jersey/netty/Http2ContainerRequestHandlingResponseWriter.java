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

import java.util.Objects;

import javax.ws.rs.HttpMethod;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response.Status;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2Headers;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerResponse;

public class Http2ContainerRequestHandlingResponseWriter extends AbstractContainerRequestHandlingResponseWriter {

  private ByteBuf entityByteBuf;
  
  public Http2ContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    super(applicationHandler);
  }

  @Override
  protected final boolean writeStatusAndHeaders(final long contentLength,
                                                final ContainerResponse containerResponse) {
    Objects.requireNonNull(containerResponse);
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    
    final Http2Headers nettyHeaders = new DefaultHttp2Headers();
    nettyHeaders.status(Integer.toString(containerResponse.getStatus()));
    copyHeaders(containerResponse.getStringHeaders(), String::toLowerCase, nettyHeaders::add);

    final boolean needsOutputStream;
    if (contentLength < 0L) {
      needsOutputStream = !HttpMethod.HEAD.equalsIgnoreCase(containerResponse.getRequestContext().getMethod());
      // Don't set the Content-Length: header because the content
      // length is unknown.
    } else if (contentLength == 0L) {
      needsOutputStream = false;
      nettyHeaders.set(HttpHeaders.CONTENT_LENGTH.toLowerCase(), "0");
    } else {
      needsOutputStream = !HttpMethod.HEAD.equalsIgnoreCase(containerResponse.getRequestContext().getMethod());
      nettyHeaders.set(HttpHeaders.CONTENT_LENGTH.toLowerCase(), Long.toString(contentLength));
    }

    ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    channelHandlerContext.writeAndFlush(new DefaultHttp2HeadersFrame(nettyHeaders, contentLength == 0L), channelPromise);

    return needsOutputStream;
  }

  @Override
  protected final OutputStream createOutputStream(final long contentLength,
                                                  final ContainerResponse containerResponse) {
    final OutputStream returnValue = super.createOutputStream(contentLength, containerResponse);
    if (returnValue instanceof ByteBufOutputStream) {
      this.entityByteBuf = ((ByteBufOutputStream)returnValue).buffer();
    }
    return returnValue;
  }

  @Override
  public final void commit() {
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    final ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    final ByteBuf entityByteBuf = this.entityByteBuf;
    try {
      if (entityByteBuf == null) {
        channelHandlerContext.writeAndFlush(new DefaultHttp2DataFrame(true), channelPromise);
      } else {
        channelHandlerContext.writeAndFlush(new DefaultHttp2DataFrame(entityByteBuf, true), channelPromise);
      }
    } finally {
      this.entityByteBuf = null;
      if (entityByteBuf != null) {
        entityByteBuf.release();
      }
    }
  }
  
  @Override
  protected final void writeFailureMessage(final Throwable failureCause) {
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    final ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    try {
      channelHandlerContext.write(new DefaultHttp2Headers().status(String.valueOf(Status.INTERNAL_SERVER_ERROR.getStatusCode())), channelPromise);
    } finally {
      final ByteBuf entityByteBuf = this.entityByteBuf;
      this.entityByteBuf = null;
      if (entityByteBuf != null) {
        entityByteBuf.release();
      }
    }
  }
  
}
