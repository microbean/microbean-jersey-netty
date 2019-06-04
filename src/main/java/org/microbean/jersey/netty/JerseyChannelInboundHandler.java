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

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import org.glassfish.jersey.internal.inject.InjectionManager;

import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ApplicationHandler;

import org.glassfish.jersey.server.internal.ContainerUtils;

import org.glassfish.jersey.spi.ExecutorServiceProvider;

public class JerseyChannelInboundHandler extends SimpleChannelInboundHandler<HttpObject> {

  private final URI baseUri;

  private final ApplicationHandler applicationHandler;

  private volatile ByteBufQueue byteBufQueue;

  public JerseyChannelInboundHandler(final URI baseUri,
                                     final ApplicationHandler applicationHandler) {
    super();
    this.baseUri = Objects.requireNonNull(baseUri);
    this.applicationHandler = Objects.requireNonNull(applicationHandler);
  }

  @Override
  protected final void channelRead0(final ChannelHandlerContext channelHandlerContext, final HttpObject message) throws Exception {
    this.messageReceived(channelHandlerContext, message);
  }

  protected final void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpObject message) throws Exception {
    if (message instanceof HttpRequest) {
      this.messageReceived(channelHandlerContext, (HttpRequest)message);
    } else if (message instanceof HttpContent) {
      this.messageReceived(channelHandlerContext, (HttpContent)message);
    } else {
      throw new IllegalArgumentException("!(message instanceof HttpRequest || message instanceof HttpContent): " + message);
    }
  }

  protected final void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpRequest httpRequest) throws Exception {
    if (HttpUtil.is100ContinueExpected(httpRequest)) {
      channelHandlerContext.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    }
    assert this.byteBufQueue == null;
    final ContainerRequest containerRequest = createContainerRequest(channelHandlerContext, httpRequest);
    
    final InjectionManager injectionManager = this.applicationHandler.getInjectionManager();
    containerRequest.setWriter(new NettyContainerResponseWriter(httpRequest, channelHandlerContext, injectionManager));

    // must be like this, since there is a blocking read from Jersey
    injectionManager.getInstance(ExecutorServiceProvider.class).getExecutorService().execute(new Runnable() {
        @Override
        public final void run() {
          applicationHandler.handle(containerRequest);
        }
      });
  }

  protected final void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpContent httpContent) throws Exception {
    Objects.requireNonNull(httpContent);

    final ByteBuf content = httpContent.content();
    assert content != null;

    // TODO: handle what amounts to an input stream coming from the caller.
    
    if (httpContent instanceof LastHttpContent) {
      assert !content.isReadable();
      this.byteBufQueue = null;
    }
    


    /*
            ByteBuf content = httpContent.content();

            if (content.isReadable()) {
                isList.add(new ByteBufInputStream(content));
            }

            if (msg instanceof LastHttpContent) {
                isList.add(NettyInputStream.END_OF_INPUT);
            }
    */
  }

  private ContainerRequest createContainerRequest(ChannelHandlerContext channelHandlerContext, HttpRequest httpRequest) {

    final String uriString = httpRequest.uri();
    final URI requestUri = baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(uriString.startsWith("/") && uriString.length() > 1 ? uriString.substring(1) : uriString));

    final ContainerRequest containerRequest =
      new ContainerRequest(this.baseUri,
                           requestUri,
                           httpRequest.method().name(),
                           new NettySecurityContext(channelHandlerContext),
                           new MapBackedPropertiesDelegate());

    final HttpHeaders headers = httpRequest.headers();
    assert headers != null;
    
    
    // request entity handling.
    if ((headers.contains(HttpHeaderNames.CONTENT_LENGTH) && HttpUtil.getContentLength(httpRequest) > 0) || HttpUtil.isTransferEncodingChunked(httpRequest)) {
      // TODO: if the channel is closed, ensure the input stream is also closed or at least throws IOException
      final EventLoopPinnedByteBufInputStream entityStream = new EventLoopPinnedByteBufInputStream(channelHandlerContext.alloc().compositeBuffer(), channelHandlerContext.executor());
      assert this.byteBufQueue == null;
      this.byteBufQueue = entityStream;
      containerRequest.setEntityStream(entityStream);
    } else {
      containerRequest.setEntityStream(new InputStream() {
          @Override
          public final int read() throws IOException {
            return -1;
          }
        });
    }

    // copying headers from netty request to jersey container request context.
    for (final String name : headers.names()) {
      containerRequest.headers(name, headers.getAll(name));
    }

    return containerRequest;
  }


}
