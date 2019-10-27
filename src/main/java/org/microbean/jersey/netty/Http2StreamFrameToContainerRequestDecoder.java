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

import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;

import org.glassfish.jersey.server.ContainerRequest;

public class Http2StreamFrameToContainerRequestDecoder extends AbstractContainerRequestDecoder<Http2StreamFrame, Http2HeadersFrame, Http2DataFrame> {

  private static final String cn = Http2StreamFrameToContainerRequestDecoder.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);

  public Http2StreamFrameToContainerRequestDecoder(final URI baseUri) {
    super(baseUri, Http2HeadersFrame.class, Http2DataFrame.class);
  }

  @Override
  protected final String getRequestUriString(final Http2HeadersFrame http2HeadersFrame) {
    return http2HeadersFrame.headers().path().toString();
  }

  @Override
  protected final String getMethod(final Http2HeadersFrame http2HeadersFrame) {
    return http2HeadersFrame.headers().method().toString();
  }

  @Override
  protected final boolean isLast(final Http2StreamFrame http2StreamFrame) {
    final boolean returnValue;
    if (http2StreamFrame instanceof Http2HeadersFrame) {
      returnValue = ((Http2HeadersFrame)http2StreamFrame).isEndStream();
    } else {
      returnValue = ((Http2DataFrame)http2StreamFrame).isEndStream();
    }
    return returnValue;
  }
  
}
