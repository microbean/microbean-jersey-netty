/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019–2020 microBean™.
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

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.List; // for javadoc only

import java.util.function.Supplier;

import javax.ws.rs.core.Configuration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;

import io.netty.channel.ChannelHandlerContext; // for javadoc only

import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

import org.glassfish.jersey.server.ContainerRequest;

/**
 * An {@link AbstractContainerRequestDecoder} that {@linkplain
 * #decode(ChannelHandlerContext, Object, List) decodes} {@link
 * HttpObject}s into {@link ContainerRequest}s.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #decode(ChannelHandlerContext, Object, List)
 */
public final class HttpObjectToContainerRequestDecoder extends AbstractContainerRequestDecoder<HttpObject, HttpRequest, HttpContent> {


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link HttpObjectToContainerRequestDecoder}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @see #HttpObjectToContainerRequestDecoder(URI, Configuration)
   *
   * @deprecated Please use the {@link
   * #HttpObjectToContainerRequestDecoder(URI, Supplier)}
   * constructor instead.
   */
  @Deprecated
  public HttpObjectToContainerRequestDecoder(final URI baseUri) {
    this(baseUri, (Supplier<? extends Configuration>)null);
  }

  /**
   * Creates a new {@link HttpObjectToContainerRequestDecoder}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param configuration a {@link Configuration} describing how the
   * container is configured; may be {@code null}
   */
  public HttpObjectToContainerRequestDecoder(final URI baseUri, final Configuration configuration) {
    this(baseUri, configuration == null ? (Supplier<? extends Configuration>)null : new ImmutableSupplier<>(configuration));
  }

  /**
   * Creates a new {@link HttpObjectToContainerRequestDecoder}.
   *
   * @param baseUri a {@link URI} that will serve as the {@linkplain
   * ContainerRequest#getBaseUri() base <code>URI</code>} in a new
   * {@link ContainerRequest}; may be {@code null} in which case the
   * return value of {@link URI#create(String) URI.create("/")} will
   * be used instead
   *
   * @param configurationSupplier a {@link Supplier} of {@link
   * Configuration} instances describing how the container is
   * configured; may be {@code null}
   */
  public HttpObjectToContainerRequestDecoder(final URI baseUri, final Supplier<? extends Configuration> configurationSupplier) {
    super(baseUri, configurationSupplier, HttpRequest.class, HttpContent.class);
  }


  /*
   * Instance methods.
   */


  /**
   * Extracts and returns a {@link String} representing a request URI
   * from the supplied message, which is guaranteed to be a
   * {@linkplain #isHeaders(Object) "headers" message}.
   *
   * <p>This implementation calls {@link HttpRequest#uri()
   * httpRequest.uri()} and returns the result.</p>
   *
   * @param httpRequest the message to interrogate; will not be {@code
   * null}
   *
   * @return a {@link String} representing a request URI from the
   * supplied message, or {@code null}
   */
  @Override
  protected final String getRequestUriString(final HttpRequest httpRequest) {
    return httpRequest.uri();
  }

  /**
   * Extracts and returns the name of the request method from the
   * supplied message, which is guaranteed to be a {@linkplain
   * #isHeaders(Object) "headers" message}.
   *
   * <p>This implementation calls {@link HttpRequest#method()
   * httpRequest.method().name()} and returns the result.</p>
   *
   * @param httpRequest the message to interrogate; will not be {@code
   * null}
   *
   * @return a {@link String} representing the request method from the
   * supplied message, or {@code null}
   */
  @Override
  protected final String getMethod(final HttpRequest httpRequest) {
    return httpRequest.method().name();
  }

  /**
   * Overrides the {@link
   * AbstractContainerRequestDecoder#installMessage(ChannelHandlerContext,
   * Object, ContainerRequest)} method to
   * <strong>additionally</strong> install incoming headers into the
   * supplied {@link ContainerRequest}.
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; will not be {@code null}; supplied for convenience;
   * overrides may (and often do) ignore this parameter
   *
   * @param message the message to install; will not be {@code null}
   *
   * @param containerRequest the just-constructed {@link
   * ContainerRequest} into which to install the supplied {@code
   * message}; will not be {@code null}
   *
   * @see HttpRequest#headers()
   *
   * @see
   * org.glassfish.jersey.message.internal.InboundMessageContext#header(String,
   * Object)
   */
  @Override
  protected void installMessage(final ChannelHandlerContext channelHandlerContext,
                                final HttpRequest message,
                                final ContainerRequest containerRequest) {
    super.installMessage(channelHandlerContext, message, containerRequest);
    final HttpHeaders headers = message.headers();
    if (headers != null && !headers.isEmpty()) {
      final Iterator<? extends Entry<?, ?>> iterator = headers.iteratorCharSequence();
      while (iterator.hasNext()) {
        final Entry<?, ?> entry = iterator.next();
        containerRequest.header(entry.getKey().toString(), entry.getValue());
      }
    }
  }

  /**
   * Returns {@code true} if the supplied {@link HttpObject} is the
   * last of a stream of messages.
   *
   * <p>This implementation returns {@code true} if either:</p>
   *
   * <ul>
   *
   * <li>{@code httpObject} is an instance of {@link LastHttpContent},
   * or</li>
   *
   * <li>{@code httpObject} is an instance of {@link HttpRequest} and
   * its {@linkplain HttpUtil#getContentLength(HttpMessage, long)
   * content length} equals {@code 0L}</li>
   *
   * </ul>
   *
   * @param httpObject the message to interrogate; will not be {@code
   * null}
   *
   * @return {@code true} if no further messages in the stream are
   * forthcoming; {@code false} otherwise
   */
  @Override
  protected final boolean isLast(final HttpObject httpObject) {
    return httpObject instanceof LastHttpContent || (httpObject instanceof HttpRequest && HttpUtil.getContentLength((HttpRequest)httpObject, -1L) == 0L);
  }

}
