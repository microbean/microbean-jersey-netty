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

import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import org.glassfish.jersey.server.ContainerRequest;

public final class HttpObjectToContainerRequestDecoder extends AbstractContainerRequestDecoder<HttpObject> {

  private static final String cn = HttpObjectToContainerRequestDecoder.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);

  public HttpObjectToContainerRequestDecoder() {
    this(null);
  }
  
  public HttpObjectToContainerRequestDecoder(final URI baseUri) {
    super(baseUri);
  }

  @Override
  public final boolean acceptInboundMessage(final Object message) {
    return message instanceof HttpRequest || message instanceof HttpContent;
  }

  @Override
  protected final boolean isHeaders(final HttpObject httpObject) {
    return httpObject instanceof HttpRequest;
  }

  @Override
  protected final String getUriString(final HttpObject httpObject) {
    return ((HttpRequest)httpObject).uri();
  }

  @Override
  protected final String getMethod(final HttpObject httpObject) {
    return ((HttpRequest)httpObject).method().name();
  }

  @Override
  protected final void installMessage(final HttpObject httpObject, final ContainerRequest containerRequest) {
    containerRequest.setProperty("org.microbean.jersey.netty.HttpRequest", httpObject);
  }

  @Override
  protected final boolean isData(final HttpObject httpObject) {
    return httpObject instanceof HttpContent;
  }

  @Override
  protected final ByteBuf getContent(final HttpObject httpObject) {
    return ((ByteBufHolder)httpObject).content();
  }

  @Override
  protected final boolean isLast(final HttpObject httpObject) {
    final boolean returnValue;
    if (httpObject instanceof HttpMessage) {
      final HttpMessage message = (HttpMessage)httpObject;
      returnValue =
        HttpUtil.getContentLength(message, -1L) > 0L ||
        HttpUtil.isTransferEncodingChunked(message);
    } else {
      returnValue = httpObject instanceof LastHttpContent;
    }
    return returnValue;
  }

}
