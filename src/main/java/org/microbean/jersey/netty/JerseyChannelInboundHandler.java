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
import io.netty.buffer.CompositeByteBuf;

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
    Objects.requireNonNull(channelHandlerContext);
    assert channelHandlerContext.executor().inEventLoop();
    
    if (message instanceof HttpRequest) {
      this.messageReceived(channelHandlerContext, (HttpRequest)message);
    } else if (message instanceof HttpContent) {
      this.messageReceived(channelHandlerContext, (HttpContent)message);
    } else {
      throw new IllegalArgumentException("!(message instanceof HttpRequest || message instanceof HttpContent): " + message);
    }
  }

  protected void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpRequest httpRequest) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    assert channelHandlerContext.executor().inEventLoop();
    
    if (HttpUtil.is100ContinueExpected(httpRequest)) {
      channelHandlerContext.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    }
    
    assert this.byteBufQueue == null;

    final ContainerRequest containerRequest = this.createContainerRequest(channelHandlerContext, httpRequest);
    
    final InjectionManager injectionManager = this.applicationHandler.getInjectionManager();
    assert injectionManager != null;
    
    containerRequest.setWriter(new NettyContainerResponseWriter(httpRequest, channelHandlerContext, injectionManager));

    injectionManager.getInstance(ExecutorServiceProvider.class).getExecutorService().execute(() -> this.applicationHandler.handle(containerRequest));
  }

  protected void messageReceived(final ChannelHandlerContext channelHandlerContext, final HttpContent httpContent) throws Exception {
    Objects.requireNonNull(channelHandlerContext);
    Objects.requireNonNull(httpContent);
    assert channelHandlerContext.executor().inEventLoop();
    
    final ByteBuf content = httpContent.content();
    assert content != null;
    
    if (httpContent instanceof LastHttpContent) {
      assert !content.isReadable();
      final ByteBufQueue byteBufQueue = this.byteBufQueue;
      if (byteBufQueue != null) {
        try {
          byteBufQueue.close();
        } finally {
          this.byteBufQueue = null;
        }
      }
    } else {
      assert this.byteBufQueue != null;
      this.byteBufQueue.addByteBuf(content.asReadOnly());
    }
  }

  protected ContainerRequest createContainerRequest(final ChannelHandlerContext channelHandlerContext, final HttpRequest httpRequest) {
    Objects.requireNonNull(channelHandlerContext);
    Objects.requireNonNull(httpRequest);
    assert channelHandlerContext.executor().inEventLoop();
    
    final String uriString = httpRequest.uri();

    final ContainerRequest returnValue =
      new ContainerRequest(this.baseUri,
                           baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(uriString.startsWith("/") && uriString.length() > 1 ? uriString.substring(1) : uriString)),
                           httpRequest.method().name(),
                           new NettySecurityContext(channelHandlerContext),
                           new MapBackedPropertiesDelegate());

    final HttpHeaders headers = httpRequest.headers();
    assert headers != null;
    final Iterable<? extends String> headerNames = headers.names();
    if (headerNames != null) {
      for (final String headerName : headerNames) {
        if (headerName != null) {
          returnValue.headers(headerName, headers.getAll(headerName));
        }
      }
    }
    
    if (HttpUtil.getContentLength(httpRequest, -1L) > 0 || HttpUtil.isTransferEncodingChunked(httpRequest)) {
      // This CompositeByteBuf will be released by EventLoopPinnedByteBufInputStream#close().
      final CompositeByteBuf compositeByteBuf = channelHandlerContext.alloc().compositeBuffer();
      final EventLoopPinnedByteBufInputStream entityStream = new EventLoopPinnedByteBufInputStream(compositeByteBuf, channelHandlerContext.executor());

      channelHandlerContext.channel().closeFuture().addListener(ignored -> entityStream.close());
      
      assert this.byteBufQueue == null;
      this.byteBufQueue = entityStream;
      returnValue.setEntityStream(entityStream);
    } else {
      returnValue.setEntityStream(UnreadableInputStream.instance);
    }

    return returnValue;
  }


  /*
   * Inner and nested classes.
   */
  

  private static final class UnreadableInputStream extends InputStream {

    private static final InputStream instance = new UnreadableInputStream();
    
    private UnreadableInputStream() {
      super();
    }

    @Override
    public final int read() throws IOException {
      return -1;
    }
    
  }

}
