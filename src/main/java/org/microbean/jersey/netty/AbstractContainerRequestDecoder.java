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

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.SecurityContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.codec.MessageToMessageDecoder;

import org.glassfish.jersey.server.ContainerRequest;

import org.glassfish.jersey.server.internal.ContainerUtils;

public abstract class AbstractContainerRequestDecoder<T> extends MessageToMessageDecoder<T> {

  private static final String cn = AbstractContainerRequestDecoder.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);
  
  private final URI baseUri;

  private ByteBufQueue byteBufQueue;
  
  private ContainerRequest containerRequestUnderConstruction;

  protected AbstractContainerRequestDecoder(final URI baseUri) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
  }

  protected abstract boolean isHeaders(final T message);

  protected abstract boolean isData(final T message);

  protected abstract String getUriString(final T message);

  protected abstract String getMethod(final T message);

  protected abstract void installMessage(final T message, final ContainerRequest containerRequest);

  protected abstract boolean isLast(final T message);

  protected abstract ByteBuf getContent(final T message);

  protected SecurityContext createSecurityContext(final T message) {
    return null; // TODO 
  }
  
  @Override
  protected final void decode(final ChannelHandlerContext channelHandlerContext,
                              final T message,
                              final List<Object> out) {
    if (isHeaders(message)) {
      if (this.containerRequestUnderConstruction == null) {
        if (this.byteBufQueue == null) {
          final String uriString = this.getUriString(message);
          final String method = this.getMethod(message);
          final SecurityContext securityContext = this.createSecurityContext(message);
          final ContainerRequest containerRequest =
            new ContainerRequest(this.baseUri,
                                 this.baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(uriString.startsWith("/") && uriString.length() > 1 ? uriString.substring(1) : uriString)),
                                 method,
                                 securityContext == null ? new SecurityContextAdapter() : securityContext,
                                 new MapBackedPropertiesDelegate());
          this.installMessage(message, containerRequest);
          if (this.isLast(message)) {
            out.add(containerRequest);
          } else {
            this.containerRequestUnderConstruction = containerRequest;
          }
        } else {
          throw new IllegalStateException("this.byteBufQueue != null: " + this.byteBufQueue);
        }
      } else {
        throw new IllegalStateException("this.containerRequestUnderConstruction != null: " + this.containerRequestUnderConstruction);
      }
    } else if (this.isData(message)) {
      final ByteBuf content = this.getContent(message);
      // final Http2DataFrame http2DataFrame = (Http2DataFrame)message;
      // final ByteBuf content = http2DataFrame.content();
      if (content == null || content.readableBytes() <= 0) {
        if (this.isLast(message)) {
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
      } else if (this.isLast(message)) {
        final ByteBufQueue byteBufQueue;
        if (this.byteBufQueue == null) {
          final TerminableByteBufInputStream entityStream = new TerminableByteBufInputStream(channelHandlerContext.alloc());
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
          final TerminableByteBufInputStream entityStream = new TerminableByteBufInputStream(channelHandlerContext.alloc());
          this.byteBufQueue = entityStream;
          this.containerRequestUnderConstruction.setEntityStream(entityStream);
          out.add(this.containerRequestUnderConstruction);
        }
        this.byteBufQueue.addByteBuf(content);
      }
    } else {
      throw new IllegalArgumentException("Unexpected message: " + message);
    }
  }
  
}
