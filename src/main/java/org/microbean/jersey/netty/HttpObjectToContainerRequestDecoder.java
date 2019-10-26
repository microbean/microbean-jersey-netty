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

import java.net.URI;

import java.util.List;
import java.util.Objects;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.SecurityContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.codec.MessageToMessageDecoder;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import org.glassfish.jersey.server.ContainerRequest;

import org.glassfish.jersey.server.internal.ContainerUtils;

public class HttpObjectToContainerRequestDecoder extends MessageToMessageDecoder<HttpObject> {

  private static final String cn = HttpObjectToContainerRequestDecoder.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);
  
  private final URI baseUri;

  private ByteBufQueue byteBufQueue;
  
  private ContainerRequest containerRequestUnderConstruction;
  
  public HttpObjectToContainerRequestDecoder(final URI baseUri) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
  }

  @Override
  protected final void decode(final ChannelHandlerContext channelHandlerContext,
                              final HttpObject httpObject,
                              final List<Object> out) {
    if (httpObject instanceof HttpRequest) {
      if (this.containerRequestUnderConstruction == null) {
        if (this.byteBufQueue == null) {
          final HttpRequest httpRequest = (HttpRequest)httpObject;
          final String uriString = httpRequest.uri();
          assert uriString != null;
          final SecurityContext securityContext = null; // todo implement
          final ContainerRequest containerRequest = new ContainerRequest(this.baseUri,
                                                                         this.baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(uriString.startsWith("/") && uriString.length() > 1 ? uriString.substring(1) : uriString)),
                                                                         httpRequest.method().name(),
                                                                         securityContext == null ? new SecurityContextAdapter() : securityContext,
                                                                         new MapBackedPropertiesDelegate());
          containerRequest.setProperty("org.microbean.jersey.netty.HttpRequest", httpRequest);
          if (HttpUtil.getContentLength(httpRequest, -1L) > 0L || HttpUtil.isTransferEncodingChunked(httpRequest)) {
            this.containerRequestUnderConstruction = containerRequest;
          } else {
            out.add(containerRequest);
          }
        } else {
          throw new IllegalStateException("this.byteBufQueue != null: " + this.byteBufQueue);
        }
      } else {
        throw new IllegalStateException("this.containerRequestUnderConstruction != null: " + this.containerRequestUnderConstruction);
      }
    } else if (httpObject instanceof ByteBufHolder) { // or really ByteBufHolder
      final ByteBufHolder httpContent = (ByteBufHolder)httpObject;
      final ByteBuf content = httpContent.content();
      if (content == null || content.readableBytes() <= 0) {
        if (httpContent instanceof LastHttpContent) {
          if (this.containerRequestUnderConstruction == null) {
            if (this.byteBufQueue != null) {
              throw new IllegalStateException("this.containerRequestUnderConstruction == null && this.byteBufQueue != null: " + this.byteBufQueue);
            }
          } else {
            out.add(this.containerRequestUnderConstruction);
            this.containerRequestUnderConstruction = null;
          }
          if (this.byteBufQueue != null) {
            this.byteBufQueue.terminate();
            this.byteBufQueue = null;
          }
        } else if (this.containerRequestUnderConstruction == null) {
          throw new IllegalStateException("this.containerRequestUnderConstruction == null");
        } else {
          // We got an empty chunk in the middle for some reason; just skip it
        }
      } else if (this.containerRequestUnderConstruction == null) {
        throw new IllegalStateException("this.containerRequestUnderConstruction == null");
      } else if (httpContent instanceof LastHttpContent) {
        final ByteBufQueue byteBufQueue;
        if (this.byteBufQueue == null) {
          final TerminatableByteBufInputStream entityStream = new TerminatableByteBufInputStream(channelHandlerContext.alloc());
          this.containerRequestUnderConstruction.setEntityStream(entityStream);
          out.add(this.containerRequestUnderConstruction);
          byteBufQueue = entityStream;
        } else {
          byteBufQueue = this.byteBufQueue;
          this.byteBufQueue = null;
        }
        assert this.byteBufQueue == null;
        assert byteBufQueue != null;
        byteBufQueue.addByteBuf(content);
        byteBufQueue.terminate();
        this.containerRequestUnderConstruction = null;
      } else {
        if (this.byteBufQueue == null) {
          final TerminatableByteBufInputStream entityStream = new TerminatableByteBufInputStream(channelHandlerContext.alloc());
          this.byteBufQueue = entityStream;
          this.containerRequestUnderConstruction.setEntityStream(entityStream);
          out.add(this.containerRequestUnderConstruction);
        }
        this.byteBufQueue.addByteBuf(content);
      }
    } else {
      throw new IllegalArgumentException("Unexpected HttpObject: " + httpObject);
    }
  }
  
}
