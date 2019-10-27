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

  public Http2ContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    super(applicationHandler);
  }

  @Override
  protected final boolean writeStatusAndHeaders(final long contentLength,
                                                final ContainerResponse containerResponse) {
    Objects.requireNonNull(containerResponse);
    
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    
    final Http2Headers nettyHeaders = new DefaultHttp2Headers();
    copyHeaders(containerResponse.getStringHeaders(), String::toLowerCase, nettyHeaders::add);
    // See https://tools.ietf.org/html/rfc7540#section-8.1.2.4
    nettyHeaders.status(Integer.toString(containerResponse.getStatus()));

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

    final Object message = new DefaultHttp2HeadersFrame(nettyHeaders, !needsOutputStream);
    ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    if (needsOutputStream) {
      channelHandlerContext.write(message, channelPromise);
    } else {
      channelHandlerContext.writeAndFlush(message, channelPromise);
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
    final OutputStream returnValue = new ByteBufBackedChannelOutboundInvokingHttp2DataFrameOutputStream(this.getChannelHandlerContext(), 8192, false, null);
    return returnValue;
  }

  @Override
  public final void commit() {
    // No-op
  }
  
  @Override
  protected final void writeFailureMessage(final Throwable failureCause) {
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    channelHandlerContext.write(new DefaultHttp2Headers().status(String.valueOf(Status.INTERNAL_SERVER_ERROR.getStatusCode())), channelHandlerContext.newPromise());
  }
  
}
