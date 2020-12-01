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

import java.io.IOException;

import java.util.Objects;

import java.util.function.Supplier;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.HttpMethod;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response.Status;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame; // for javadoc only
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame; // for javadoc only

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerResponse;

import org.microbean.jersey.netty.AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator;

/**
 * An {@link AbstractContainerRequestHandlingResponseWriter}
 * implemented in terms of {@link Http2Headers}, {@link
 * Http2HeadersFrame}s, {@link Http2DataFrame}s and {@link
 * ByteBufBackedChannelOutboundInvokingHttp2DataFrameOutputStream}s.
 *
 * @author <a href="https://about.me/lairdnelson" target="_parent">Laird Nelson</a>
 *
 * @see AbstractContainerRequestHandlingResponseWriter
 *
 * @see ByteBufBackedChannelOutboundInvokingHttpContentOutputStream
 *
 * @see #writeStatusAndHeaders(long, ContainerResponse)
 *
 * @see #createOutputStream(long, ContainerResponse)
 */
public final class Http2ContainerRequestHandlingResponseWriter extends AbstractContainerRequestHandlingResponseWriter<Http2DataFrame> {


  /*
   * Static fields.
   */


  private static final String cn = Http2ContainerRequestHandlingResponseWriter.class.getName();

  private static final Logger logger = Logger.getLogger(cn);

  private static final GenericFutureListener<? extends Future<? super Void>> listener = new LoggingWriteListener(logger);


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link Http2ContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  public Http2ContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    super(() -> applicationHandler);
  }

  /**
   * Creates a new {@link Http2ContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @param flushThreshold the minimum number of bytes that an {@link
   * AbstractChannelOutboundInvokingOutputStream} returned by the
   * {@link #createOutputStream(long, ContainerResponse)} method must
   * write before an automatic {@linkplain
   * AbstractChannelOutboundInvokingOutputStream#flush() flush} may
   * take place; if less than {@code 0} {@code 0} will be used
   * instead; if {@link Integer#MAX_VALUE} then it is suggested that
   * no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be used
   * by the {@link #createOutputStream(long, ContainerResponse)}
   * method; may be {@code null}
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  public Http2ContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler,
                                                     final int flushThreshold,
                                                     final ByteBufCreator byteBufCreator) {
    super(() -> applicationHandler, flushThreshold, byteBufCreator);
  }

  /**
   * Creates a new {@link Http2ContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandlerSupplier a {@link Supplier} of an {@link
   * ApplicationHandler} representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  public Http2ContainerRequestHandlingResponseWriter(final Supplier<? extends ApplicationHandler> applicationHandlerSupplier) {
    super(applicationHandlerSupplier);
  }

  /**
   * Creates a new {@link Http2ContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandlerSupplier a {@link Supplier} of an {@link
   * ApplicationHandler} representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @param flushThreshold the minimum number of bytes that an {@link
   * AbstractChannelOutboundInvokingOutputStream} returned by the
   * {@link #createOutputStream(long, ContainerResponse)} method must
   * write before an automatic {@linkplain
   * AbstractChannelOutboundInvokingOutputStream#flush() flush} may
   * take place; if less than {@code 0} {@code 0} will be used
   * instead; if {@link Integer#MAX_VALUE} then it is suggested that
   * no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that will be used
   * by the {@link #createOutputStream(long, ContainerResponse)}
   * method; may be {@code null}
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  public Http2ContainerRequestHandlingResponseWriter(final Supplier<? extends ApplicationHandler> applicationHandlerSupplier,
                                                     final int flushThreshold,
                                                     final ByteBufCreator byteBufCreator) {
    super(applicationHandlerSupplier, flushThreshold, byteBufCreator);
  }


  /*
   * Instance methods.
   */


  /**
   * Writes the status and headers portion of the response present in
   * the supplied {@link ContainerResponse} and returns {@code true}
   * if further output is forthcoming.
   *
   * <p>This implementation writes an instance of {@link
   * DefaultHttp2HeadersFrame}.</p>
   *
   * @param contentLength the content length as determined by the
   * logic encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method; a value less
   * than zero indicates an unknown content length
   *
   * @param containerResponse the {@link ContainerResponse} containing
   * status and headers information; must not be {@code null}
   *
   * @return {@code true} if the {@link #createOutputStream(long,
   * ContainerResponse)} method should be invoked, <em>i.e.</em> if
   * further output is forthcoming
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null}
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see #createOutputStream(long, ContainerResponse)
   */
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

    final Object message = new DefaultHttp2HeadersFrame(nettyHeaders, !needsOutputStream /* end of stream? */);

    final ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    channelPromise.addListener(listener);

    // Remember that
    // AbstractContainerRequestHandlingResponseWriter#channelReadComplete(ChannelHandlerContext)
    // will call ChannelHandlerContext#flush() in all cases.
    channelHandlerContext.write(message, channelPromise);

    return needsOutputStream;
  }

  /**
   * Creates and returns a new {@link
   * ByteBufBackedChannelOutboundInvokingHttp2DataFrameOutputStream}.
   *
   * @param contentLength the content length as determined by the
   * logic encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method; must not be
   * {@code 0L}
   *
   * @param containerResponse a {@link ContainerResponse} for which an
   * {@link AbstractChannelOutboundInvokingOutputStream} is being
   * created and returned; must not be {@code null}; ignored by this
   * implementation
   *
   * @return a new {@link
   * ByteBufBackedChannelOutboundInvokingHttp2DataFrameOutputStream}
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null} or if {@link #getChannelHandlerContext()} returns
   * {@code null}
   *
   * @exception IllegalArgumentException if {@code contentLength} is
   * {@code 0L}
   *
   * @see ByteBufBackedChannelOutboundInvokingHttpContentOutputStream
   */
  @Override
  protected final AbstractChannelOutboundInvokingOutputStream<? extends Http2DataFrame> createOutputStream(final long contentLength,
                                                                                                           final ContainerResponse containerResponse) {
    if (contentLength == 0L) {
      throw new IllegalArgumentException("contentLength == 0L");
    }
    return new ByteBufBackedChannelOutboundInvokingHttp2DataFrameOutputStream(this.getChannelHandlerContext(),
                                                                              this.getFlushThreshold(),
                                                                              false,
                                                                              this.getByteBufCreator()) {
      @Override
      protected final ChannelPromise newPromise() {
        final ChannelPromise returnValue = super.newPromise();
        if (returnValue != null && !returnValue.isVoid()) {
          returnValue.addListener(listener);
        }
        return returnValue;
      }
    };
  }

  /**
   * Writes an appropriate failure message using the return value of
   * the {@link #getChannelHandlerContext()} method.
   *
   * @param failureCause the {@link Throwable} responsible for this
   * method's invocation; may be {@code null} in pathological cases;
   * ignored by this implementation
   *
   * @exception NullPointerException if {@link
   * #getChannelHandlerContext()} returns {@code null}
   */
  @Override
  protected final void writeFailureMessage(final Throwable failureCause) {
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    final ChannelPromise channelPromise = channelHandlerContext.newPromise();
    assert channelPromise != null;
    channelPromise.addListener(listener);
    channelHandlerContext.write(new DefaultHttp2Headers().status(String.valueOf(Status.INTERNAL_SERVER_ERROR.getStatusCode())),
                                channelPromise);
  }

}
