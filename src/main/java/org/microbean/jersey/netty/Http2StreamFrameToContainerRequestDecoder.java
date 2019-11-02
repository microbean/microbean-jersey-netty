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

import java.util.logging.Logger;

import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;

import org.glassfish.jersey.server.ContainerRequest;

/**
 * An {@link AbstractContainerRequestDecoder} that {@linkplain
 * #decode(ChannelHandlerContext, Object, List) decodes} {@link
 * Http2StreamFrame}s into {@link ContainerRequest}s.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #decode(ChannelHandlerContext, Object, List)
 */
public class Http2StreamFrameToContainerRequestDecoder extends AbstractContainerRequestDecoder<Http2StreamFrame, Http2HeadersFrame, Http2DataFrame> {


  /*
   * Static fields.
   */

  
  private static final String cn = Http2StreamFrameToContainerRequestDecoder.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);


  /*
   * Constructors.
   */

  
  /**
   * Creates a new {@link Http2StreamFrameToContainerRequestDecoder}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   */
  public Http2StreamFrameToContainerRequestDecoder(final URI baseUri) {
    super(baseUri, Http2HeadersFrame.class, Http2DataFrame.class);
  }


  /*
   * Instance methods.
   */
  

  /**
   * Extracts and returns a {@link String} representing a request URI
   * from the supplied message, which is guaranteed to be a
   * {@linkplain #isHeaders(Object) "headers" message}.
   *
   * <p>This implementation calls {@link Http2HeadersFrame#headers()
   * http2HeadersFrame.headers().path().toString()} and returns the
   * result.</p>
   *
   * @param http2HeadersFrame the message to interrogate; will not be
   * {@code null}
   *
   * @return a {@link String} representing a request URI from the
   * supplied message, or {@code null}
   */
  @Override
  protected final String getRequestUriString(final Http2HeadersFrame http2HeadersFrame) {
    return http2HeadersFrame.headers().path().toString();
  }

  /**
   * Extracts and returns the name of the request method from the
   * supplied message, which is guaranteed to be a {@linkplain
   * #isHeaders(Object) "headers" message}.
   *
   * <p>This implementation calls {@link Http2HeadersFrame#headers()
   * http2HeadersFrame.headers().method().toString()} and returns the
   * result.</p>
   *
   * @param http2HeadersFrame the message to interrogate; will not be
   * {@code null}
   *
   * @return a {@link String} representing the request method from the
   * supplied message, or {@code null}
   */
  @Override
  protected final String getMethod(final Http2HeadersFrame http2HeadersFrame) {
    return http2HeadersFrame.headers().method().toString();
  }

  /**
   * Returns {@code true} if the supplied {@link Http2StreamFrame} is the
   * last of a stream of messages.
   *
   * <p>This implementation returns {@code true} if either:</p>
   *
   * <ul>
   *
   * <li>{@code http2StreamFrame} is an instance of {@link
   * Http2HeadersFrame} and its {@link
   * Http2HeadersFrame#isEndStream()} method returns {@code true},
   * or</li>
   *
   * <li>{@code http2StreamFrame} is an instance of {@link
   * Http2DataFrame} and its {@link Http2DataFrame#isEndStream()}
   * method returns {@code true}</li>
   *
   * </ul>
   *
   * @param http2StreamFrame the message to interrogate; will not be
   * {@code null}
   *
   * @return {@code true} if no further messages in the stream are
   * forthcoming; {@code false} otherwise
   */
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
