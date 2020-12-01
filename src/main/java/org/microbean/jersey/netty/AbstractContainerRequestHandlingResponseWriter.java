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

import java.io.OutputStream;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;

import java.util.logging.Logger;

import io.netty.channel.Channel; // for javadoc only
import io.netty.channel.ChannelConfig; // for javadoc only
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundInvoker; // for javadoc only

import org.glassfish.jersey.CommonProperties; // for javadoc only

import org.glassfish.jersey.message.internal.CommittingOutputStream; // for javadoc only

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.Container;
import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

import org.microbean.jersey.netty.AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator;

/**
 * An abstract {@link ChannelInboundHandlerAdapter} that is also a
 * {@link ContainerResponseWriter} that processes incoming {@link
 * ContainerRequest} events, such as those dispatched by an {@link
 * AbstractContainerRequestDecoder}, by supplying them to the {@link
 * ApplicationHandler#handle(ContainerRequest)} method.
 *
 * <p>Instances of this class are in charge of properly invoking
 * {@link ApplicationHandler#handle(ContainerRequest)}, thus adapting
 * <a href="https://eclipse-ee4j.github.io/jersey/"
 * target="_parent">Jersey</a> to <a href="https://netty.io/"
 * target="_parent">Netty</a>'s constraints and vice versa.</p>
 *
 * @param <T> the type of message that will be written by instances of
 * this class; see {@link #createOutputStream(long,
 * ContainerResponse)}
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #channelRead(ChannelHandlerContext, Object)
 *
 * @see #createOutputStream(long, ContainerResponse)
 *
 * @see ChannelInboundHandlerAdapter
 *
 * @see ApplicationHandler#handle(ContainerRequest)
 *
 * @see ContainerResponseWriter
 */
public abstract class AbstractContainerRequestHandlingResponseWriter<T> extends ChannelInboundHandlerAdapter implements ContainerResponseWriter {


  /*
   * Static fields.
   */


  private static final String cn = AbstractContainerRequestHandlingResponseWriter.class.getName();

  private static final Logger logger = Logger.getLogger(cn);


  /*
   * Instance fields.
   */


  private final Supplier<? extends ApplicationHandler> applicationHandlerSupplier;

  private ScheduledFuture<?> suspendTimeoutFuture;

  private Runnable suspendTimeoutHandler;

  private ChannelHandlerContext channelHandlerContext;

  private final int flushThreshold;

  private final ByteBufCreator byteBufCreator;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link
   * AbstractContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandler an {@link ApplicationHandler}
   * representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see #AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see #channelRead(ChannelHandlerContext, Object)
   */
  protected AbstractContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    this(() -> applicationHandler, 8192, null);
  }

  /**
   * Creates a new {@link
   * AbstractContainerRequestHandlingResponseWriter}.
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
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}
   * returned by the {@link #createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#flush()
   * flush} may take place; if less than {@code 0} {@code 0} will be
   * used instead; if {@code Integer#MAX_VALUE} then it is suggested
   * that no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that may be used
   * (but does not have to be used) by the implementation of the
   * {@link #createOutputStream(long, ContainerResponse)} method; may
   * be {@code null}
   *
   * @see #AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see #channelRead(ChannelHandlerContext, Object)
   *
   * @see #getFlushThreshold()
   *
   * @see #getByteBufCreator()
   *
   * @see #createOutputStream(long, ContainerResponse)
   */
  protected AbstractContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler,
                                                           final int flushThreshold,
                                                           final ByteBufCreator byteBufCreator) {
    this(() -> applicationHandler, flushThreshold, byteBufCreator);
  }

  /**
   * Creates a new {@link
   * AbstractContainerRequestHandlingResponseWriter}.
   *
   * @param applicationHandlerSupplier a {@link Supplier} of an {@link
   * ApplicationHandler} representing a <a
   * href="https://jakarta.ee/specifications/restful-ws/"
   * target="_parent">Jakarta RESTful Web Services application</a>
   * whose {@link ApplicationHandler#handle(ContainerRequest)} method
   * will serve as the bridge between Netty and Jersey; may be {@code
   * null} somewhat pathologically but normally is not
   *
   * @see #AbstractContainerRequestHandlingResponseWriter(Supplier,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see #channelRead(ChannelHandlerContext, Object)
   */
  protected AbstractContainerRequestHandlingResponseWriter(final Supplier<? extends ApplicationHandler> applicationHandlerSupplier) {
    this(applicationHandlerSupplier, 8192, null);
  }

  /**
   * Creates a new {@link
   * AbstractContainerRequestHandlingResponseWriter}.
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
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream}
   * returned by the {@link #createOutputStream(long,
   * ContainerResponse)} method must write before an automatic
   * {@linkplain
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#flush()
   * flush} may take place; if less than {@code 0} {@code 0} will be
   * used instead; if {@code Integer#MAX_VALUE} then it is suggested
   * that no automatic flushing will occur
   *
   * @param byteBufCreator a {@link ByteBufCreator} that may be used
   * (but does not have to be used) by the implementation of the
   * {@link #createOutputStream(long, ContainerResponse)} method; may
   * be {@code null}
   *
   * @see ApplicationHandler
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see #channelRead(ChannelHandlerContext, Object)
   *
   * @see #getFlushThreshold()
   *
   * @see #getByteBufCreator()
   *
   * @see #createOutputStream(long, ContainerResponse)
   */
  protected AbstractContainerRequestHandlingResponseWriter(final Supplier<? extends ApplicationHandler> applicationHandlerSupplier,
                                                           final int flushThreshold,
                                                           final ByteBufCreator byteBufCreator) {
    super();
    if (applicationHandlerSupplier == null) {
      this.applicationHandlerSupplier = () -> new ApplicationHandler();
    } else {
      this.applicationHandlerSupplier = applicationHandlerSupplier;
    }
    this.flushThreshold = Math.max(0, flushThreshold);
    this.byteBufCreator = byteBufCreator;
  }


  /*
   * Instance methods.
   */


  /**
   * Overrides {@link
   * ChannelInboundHandlerAdapter#channelActive(ChannelHandlerContext)}
   * to ensure that a channel read is performed one way or another.
   *
   * <p>If {@linkplain ChannelConfig#isAutoRead() autoread is active},
   * then the superclass' version of this method is executed with no
   * changes.  If {@linkplain ChannelConfig#isAutoRead() autoread is
   * inactive}, then a call is made to {@link
   * ChannelHandlerContext#read()}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelHandlerContext}
   * is {@code null}
   *
   * @exception Exception if an error occurs
   *
   * @see ChannelConfig#isAutoRead()
   *
   * @see Channel#read()
   *
   * @see ChannelHandlerContext#read()
   *
   * @see #channelRead(ChannelHandlerContext, Object)
   */
  @Override
  public final void channelActive(final ChannelHandlerContext channelHandlerContext) throws Exception {
    super.channelActive(channelHandlerContext);
    // See
    // https://github.com/netty/netty/blob/d446765b8469ca40db40f46e5c637d980b734a8a/transport/src/main/java/io/netty/channel/DefaultChannelPipeline.java#L1408-L1413.
    if (!channelHandlerContext.channel().config().isAutoRead()) {
      // If autoRead was "on", then the superclass version of this
      // method will have already performed a read from the channel
      // (e.g. channel.read()).  If autoRead is "off", then we get
      // here, and we do a read from the ChannelHandlerContext (and so
      // we don't have to traverse the whole pipeline).
      channelHandlerContext.read();
    }
  }

  /**
   * If the supplied {@code message} is a {@link ContainerRequest}
   * then this method will {@linkplain
   * ContainerRequest#setWriter(ContainerResponseWriter) install
   * itself as that request's <code>ContainerResponseWriter</code>}
   * and will invoke {@link
   * ApplicationHandler#handle(ContainerRequest)}.
   *
   * <p>In all other cases this method will simply call {@link
   * ChannelInboundHandlerAdapter#channelRead(ChannelHandlerContext,
   * Object)} with the supplied {@code message}.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; must not be {@code null}
   *
   * @param message the incoming message, or event; may be {@code null}
   *
   * @exception NullPointerException if {@code channelHandlerContext}
   * is {@code null}, or if the {@link Supplier} of {@link
   * ApplicationHandler} instances supplied at construction time
   * returns {@code null}
   *
   * @exception Exception if {@code message} is not an instance of
   * {@link ContainerRequest} and {@link
   * ChannelInboundHandlerAdapter#channelRead(ChannelHandlerContext,
   * Object)} throws an {@link Exception}
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   *
   * @see ContainerResponseWriter
   *
   * @see ContainerRequest#setWriter(ContainerResponseWriter)
   */
  @Override
  public final void channelRead(final ChannelHandlerContext channelHandlerContext,
                                final Object message)
    throws Exception {
    if (this.getChannelHandlerContext() != null) {
      throw new IllegalStateException("this.getChannelHandlerContext() != null: " + this.getChannelHandlerContext());
    }
    this.channelHandlerContext = Objects.requireNonNull(channelHandlerContext);
    try {
      if (message instanceof ContainerRequest) {
        final ContainerRequest containerRequest = (ContainerRequest)message;
        containerRequest.setWriter(this);
        this.applicationHandlerSupplier.get().handle(containerRequest);
      } else {
        super.channelRead(channelHandlerContext, message);
      }
    } finally {
      this.channelHandlerContext = null;
    }
  }

  /**
   * Overrides the {@link
   * ChannelInboundHandlerAdapter#channelReadComplete(ChannelHandlerContext)}
   * method to call {@link ChannelHandlerContext#flush()
   * channelHandlerContext.flush()} before calling the superclass
   * implementation.
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelHandlerContext} is {@code null}
   *
   * @exception Exception if {@link
   * ChannelInboundHandlerAdapter#channelReadComplete(ChannelHandlerContext)}
   * throws an {@link Exception}
   */
  @Override
  public final void channelReadComplete(final ChannelHandlerContext channelHandlerContext) throws Exception {
    channelHandlerContext.flush();
    // See
    // https://github.com/netty/netty/blob/d446765b8469ca40db40f46e5c637d980b734a8a/transport/src/main/java/io/netty/channel/DefaultChannelPipeline.java#L1408-L1413.
    super.channelReadComplete(channelHandlerContext);
    if (!channelHandlerContext.channel().config().isAutoRead()) {
      // Ultimately a read is just an idempotent call so even if other
      // handlers do this seemingly nothing bad will happen.  See
      // https://github.com/netty/netty/blob/9976ab7fe86e052d29ca7accf528c885e93dcb4c/transport/src/main/java/io/netty/channel/nio/AbstractNioChannel.java#L402-L416.
      // Even in the ancient case of blocking IO it's still
      // idempotent:
      // https://github.com/netty/netty/blob/9976ab7fe86e052d29ca7accf528c885e93dcb4c/transport/src/main/java/io/netty/channel/oio/AbstractOioChannel.java#L101-L109
      // Finally, transports like Epoll are less clear:
      // https://github.com/netty/netty/blob/9976ab7fe86e052d29ca7accf528c885e93dcb4c/transport-native-epoll/src/main/java/io/netty/channel/epoll/AbstractEpollChannel.java#L226-L242
      // ...but Epoll too ultimately is idempotent:
      // https://github.com/netty/netty/blob/9976ab7fe86e052d29ca7accf528c885e93dcb4c/transport-native-epoll/src/main/java/io/netty/channel/epoll/AbstractEpollChannel.java#L226-L242
      channelHandlerContext.read();
    }
  }

  /**
   * Returns the {@link ChannelHandlerContext} in effect, or {@code
   * null} if there is no such {@link ChannelHandlerContext}.
   *
   * <p>This method may return {@code null}.</p>
   *
   * @return the {@link ChannelHandlerContext} in effect, or {@code
   * null} if there is no such {@link ChannelHandlerContext}
   */
  protected final ChannelHandlerContext getChannelHandlerContext() {
    return this.channelHandlerContext;
  }

  /*
   * ContainerResponseWriter overrides.
   */

  /**
   * Returns {@code true} when invoked to indicate that buffering of
   * entity content is supported and can be configured to be on or
   * off.
   *
   * <p>Note that the return value of this method is a default value
   * indicating that the <em>concept</em> of response buffering is
   * enabled.  The actual <em>configuration</em> of response buffering
   * is a property of the application.  Specifically, an application's
   * response buffering policy <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778"
   * target="_parent">can be configured</a>: if the application's
   * configuration sets the {@link
   * CommonProperties#OUTBOUND_CONTENT_LENGTH_BUFFER
   * jersey.config.contentLength.buffer} property to a positive {@code
   * int}, then buffering will occur, and if the application's
   * configuration sets the {@link
   * CommonProperties#OUTBOUND_CONTENT_LENGTH_BUFFER
   * jersey.config.contentLength.buffer} property to a negative {@code
   * int}, then buffering will not occur.  If the application's
   * configuration does nothing in this regard, response buffering
   * will be enabled with a default buffer size of {@link
   * CommittingOutputStream#DEFAULT_BUFFER_SIZE 8192}.</p>
   *
   * <p>(If, instead, this method had been written to return {@code
   * false}, then no matter what configuration settings an application
   * might specify in the realm of response buffering settings,
   * response buffering of any kind would never be possible.)</p>
   *
   * @return {@code true} when invoked; never {@code false}
   *
   * @see ContainerResponseWriter#enableResponseBuffering()
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/8dcbaf4b5cb0fb345eb949acfa66b1fbe09d1ffb/core-server/src/main/java/org/glassfish/jersey/server/ServerRuntime.java#L634"
   * target="_parent"><code>ServerRuntime.java</code></a>
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-server/src/main/java/org/glassfish/jersey/server/ContainerResponse.java#L352-L363"
   * target="_parent"><code>ContainerResponse.java</code></a>
   *
   * @see <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778"
   * target="_parent"><code>OutboundMessageContext.java</code></a>
   */
  @Override
  public final boolean enableResponseBuffering() {
    return true;
  }

  /**
   * Writes the status and headers portion of the response present in
   * the supplied {@link ContainerResponse} by calling the {@link
   * #writeStatusAndHeaders(long, ContainerResponse)} method, and, if
   * the supplied {@code contentLength} is not {@code 0L} and that
   * method returns {@code true} indicating that output will be
   * forthcoming, returns the result of invoking {@link
   * #createOutputStream(long, ContainerResponse)}.
   *
   * <p>In all other cases, this method returns {@code null}.</p>
   *
   * @param contentLength the content length as determined by the
   * logic encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method; a value less
   * than zero indicates an unknown content length
   *
   * @param containerResponse the {@link ContainerResponse} containing
   * status and headers information; must not be {@code null}
   *
   * @return the {@link OutputStream} returned by the {@link
   * #createOutputStream(long, ContainerResponse)} method, or {@code
   * null}
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null}
   *
   * @see #writeStatusAndHeaders(long, ContainerResponse)
   *
   * @see #createOutputStream(long, ContainerResponse)
   *
   * @see ApplicationHandler#handle(ContainerRequest)
   */
  @Override
  public final OutputStream writeResponseStatusAndHeaders(final long contentLength,
                                                          final ContainerResponse containerResponse) {
    final OutputStream returnValue;
    if (this.writeStatusAndHeaders(contentLength, Objects.requireNonNull(containerResponse)) && contentLength != 0L) {
      returnValue = this.createOutputStream(contentLength, containerResponse);
    } else {
      returnValue = null;
    }
    return returnValue;
  }

  /**
   * Writes the status and headers portion of the response present in
   * the supplied {@link ContainerResponse} and returns {@code true}
   * if further output is forthcoming.
   *
   * <p>Implementations of this method must not call the {@link
   * #writeResponseStatusAndHeaders(long, ContainerResponse)} method
   * or an infinite loop may result.</p>
   *
   * <p>Implementations of this method must not call the {@link
   * #createOutputStream(long, ContainerResponse)} method or undefined
   * behavior may result.</p>
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
  protected abstract boolean writeStatusAndHeaders(final long contentLength,
                                                   final ContainerResponse containerResponse);

  /**
   * Creates and returns a new {@link
   * AbstractChannelOutboundInvokingOutputStream}, or returns {@code
   * null} if it is determined that no {@link
   * AbstractChannelOutboundInvokingOutputStream} is required given
   * the supplied {@code contentLength} parameter value.
   *
   * <p>Implementations of this method may return {@code null}.</p>
   *
   * @param contentLength the content length as determined by the
   * logic encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method; a value less
   * than zero indicates an unknown content length; must not be equal
   * to {@code 0L}
   *
   * @param containerResponse the {@link ContainerResponse} containing
   * status and headers information; must not be {@code null}; may be
   * (and often is) ignored by implementations
   *
   * @return a new {@link AbstractChannelOutboundInvokingOutputStream}
   * implementation, or {@code null}
   *
   * @exception NullPointerException if {@code containerResponse} is
   * {@code null}
   *
   * @exception IllegalArgumentException if {@code contentLength} is
   * equal to {@code 0L}
   */
  protected abstract AbstractChannelOutboundInvokingOutputStream<? extends T> createOutputStream(final long contentLength,
                                                                                                 final ContainerResponse containerResponse);

  /**
   * Returns the minimum number of bytes that an {@link
   * AbstractChannelOutboundInvokingOutputStream} returned by the
   * {@link #createOutputStream(long, ContainerResponse)} method must
   * write before an automatic {@linkplain
   * AbstractChannelOutboundInvokingOutputStream#flush() flush} may
   * take place.
   *
   * <p><strong>Note:</strong> Implementations of the {@link
   * #createOutputStream(long, ContainerResponse)} method may choose
   * to ignore the return value of this method.  It is supplied for
   * convenience only for use by implementors of the {@link
   * #createOutputStream(long, ContainerResponse)} method.</p>
   *
   * @return the minimum number of bytes that an {@link
   * AbstractChannelOutboundInvokingOutputStream} returned by the
   * {@link #createOutputStream(long, ContainerResponse)} method must
   * write before an automatic {@linkplain
   * AbstractChannelOutboundInvokingOutputStream#flush() flush} may
   * take place; always {@code 0L} or a positive {@code int}; if
   * {@code 0} it is suggested that automatic flushing occur after
   * every write; if {@code Integer#MAX_VALUE} it is suggested that no
   * automatic flushing should occur
   *
   * @see #createOutputStream(long, ContainerResponse)
   *
   * @see
   * #AbstractContainerRequestHandlingResponseWriter(ApplicationHandler,
   * int,
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#AbstractByteBufBackedChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean, ByteBufCreator)
   */
  protected final int getFlushThreshold() {
    return this.flushThreshold;
  }

  /**
   * Returns a {@link ByteBufCreator} that may be used to create the
   * {@link AbstractChannelOutboundInvokingOutputStream}
   * implementation that must be returned by an implementation of the
   * {@link #createOutputStream(long, ContainerResponse)} method.
   *
   * <p>This method may return {@code null}.</p>
   *
   * <p><strong>Note:</strong> Implementations of the {@link
   * #createOutputStream(long, ContainerResponse)} method may choose
   * to ignore the return value of this method.  It is supplied for
   * convenience only for use by implementors of the {@link
   * #createOutputStream(long, ContainerResponse)} method.</p>
   *
   * @return a {@link ByteBufCreator}, or {@code null}
   *
   * @see #createOutputStream(long, ContainerResponse)
   *
   * @see
   * #AbstractContainerRequestHandlingResponseWriter(ApplicationHandler,
   * int, AbstractByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator)
   *
   * @see
   * AbstractByteBufBackedChannelOutboundInvokingOutputStream#AbstractByteBufBackedChannelOutboundInvokingOutputStream(ChannelOutboundInvoker,
   * int, boolean, ByteBufCreator)
   */
  protected final ByteBufCreator getByteBufCreator() {
    return this.byteBufCreator;
  }

  /**
   * Invoked by Jersey when a {@link ContainerRequest} has been fully
   * {@linkplain ApplicationHandler#handle(ContainerRequest) handled}
   * successfully.
   *
   * <p>Either {@link #commit()} or {@link #failure(Throwable)} will
   * be called by Jersey as the logically final operation in the logic
   * encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method, but not
   * both.</p>
   *
   * <p>This implementation does nothing.</p>
   *
   * @see ContainerResponseWriter#commit()
   *
   * @see #failure(Throwable)
   */
  @Override
  public void commit() {

  }

  @Override
  public final boolean suspend(final long timeout,
                               final TimeUnit timeUnit,
                               final TimeoutHandler timeoutHandler) {
    // Lifted from Jersey's supplied Netty integration, with repairs.
    final boolean returnValue;
    if (timeoutHandler == null || this.suspendTimeoutHandler != null) {
      returnValue = false;
    } else {
      this.suspendTimeoutHandler = () -> {
        timeoutHandler.onTimeout(this);
        this.suspendTimeoutHandler = null;
      };
      if (timeout > 0L) {
        final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
        this.suspendTimeoutFuture =
          channelHandlerContext.executor().schedule(this.suspendTimeoutHandler, timeout, timeUnit);
      }
      returnValue = true;
    }
    return returnValue;
  }

  @Override
  public final void setSuspendTimeout(final long timeout, final TimeUnit timeUnit) {
    // Lifted from Jersey's supplied Netty integration, with repairs.
    if (this.suspendTimeoutHandler == null) {
      throw new IllegalStateException("this.suspendTimeoutHandler == null");
    }
    if (this.suspendTimeoutFuture != null) {
      this.suspendTimeoutFuture.cancel(true);
      this.suspendTimeoutFuture = null;
    }
    if (timeout > 0L) {
      final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
      this.suspendTimeoutFuture =
        channelHandlerContext.executor().schedule(this.suspendTimeoutHandler, timeout, timeUnit);
    }
  }

  /**
   * Handles any failure case encountered by the logic encapsulated by
   * the {@link ApplicationHandler#handle(ContainerRequest)} method.
   *
   * <p>Either {@link #commit()} or {@link #failure(Throwable)} will
   * be called by Jersey as the logically final operation in the logic
   * encapsulated by the {@link
   * ApplicationHandler#handle(ContainerRequest)} method, but not
   * both.</p>
   *
   * <p>This method calls the {@link #writeFailureMessage(Throwable)}
   * method and takes great care to ensure that any {@link Throwable}s
   * encountered along the way are properly recorded and {@linkplain
   * Throwable#addSuppressed(Throwable) suppressed}.</p>
   *
   * <p><strong>This implementation never returns.</strong> A {@link
   * ContainerException} is always thrown by this method.</p>
   *
   * @param failureCause the {@link Throwable} encountered by the
   * {@link ApplicationHandler#handle(ContainerRequest)} method; may
   * be {@code null}
   *
   * @exception ContainerException when this method is invoked; it
   * will have the supplied {@code failureCause} as its {@linkplain
   * Throwable#getCause() cause}
   *
   * @see #commit()
   *
   * @see ContainerResponseWriter#failure(Throwable)
   */
  @Override
  public final void failure(final Throwable failureCause) {
    final ChannelHandlerContext channelHandlerContext = Objects.requireNonNull(this.getChannelHandlerContext());
    Throwable outerWriteProblem = null;
    try {
      this.writeFailureMessage(failureCause);
    } catch (final RuntimeException | Error writeProblem) {
      outerWriteProblem = writeProblem;
      if (failureCause != null) {
        boolean foundFailureCauseInSuppressedThrowables = false;
        final Object[] suppressedThrowables = writeProblem.getSuppressed();
        if (suppressedThrowables != null && suppressedThrowables.length > 0) {
          for (final Object suppressedThrowable : suppressedThrowables) {
            if (suppressedThrowable == failureCause) {
              foundFailureCauseInSuppressedThrowables = true;
              break;
            }
          }
        }
        if (!foundFailureCauseInSuppressedThrowables) {
          writeProblem.addSuppressed(failureCause);
        }
      }
      throw writeProblem;
    } finally {
      Throwable outerFlushProblem = null;
      try {
        channelHandlerContext.flush();
      } catch (final RuntimeException | Error flushProblem) {
        outerFlushProblem = flushProblem;
        if (outerWriteProblem != null) {
          flushProblem.addSuppressed(outerWriteProblem);
        } else if (failureCause != null) {
          flushProblem.addSuppressed(failureCause);
        }
        throw flushProblem;
      } finally {
        try {
          channelHandlerContext.close();
        } catch (final RuntimeException | Error closeProblem) {
          if (outerFlushProblem != null) {
            closeProblem.addSuppressed(outerFlushProblem);
          } else if (failureCause != null) {
            closeProblem.addSuppressed(failureCause);
          }
          throw closeProblem;
        }
      }
    }
    if (failureCause == null) {
      throw new ContainerException("failure");
    } else if (failureCause instanceof RuntimeException) {
      throw (RuntimeException)failureCause;
    } else if (failureCause instanceof Exception) {
      throw new ContainerException(failureCause.getMessage(), failureCause);
    } else {
      throw (Error)failureCause;
    }
  }

  /**
   * Writes an appropriate message, possibly using the {@link
   * #getChannelHandlerContext() ChannelHandlerContext} to do so.
   *
   * <p>Implementations of this method must not call the {@link
   * #failure(Throwable)} method or an infinite loop may result.</p>
   *
   * @param failureCause the {@link Throwable} responsible for this
   * method's invocation; may be {@code null} in pathological cases
   */
  protected abstract void writeFailureMessage(final Throwable failureCause);


  /*
   * Static utility methods.
   */


  /**
   * A utility function that copies entries from a source {@link Map}
   * by passing each entry to the supplied {@link BiConsumer},
   * transforming the keys beforehand using the supplied {@link
   * UnaryOperator} and that is intended in this framework to be used
   * to copy HTTP or HTTP/2 headers to and from the proper places.
   *
   * @param headersSource the source of the headers to copy; may be
   * {@code null} in which case no action will be taken
   *
   * @param keyTransformer a {@link UnaryOperator} that transforms a
   * header name; if {@code null} then the return value of {@link
   * UnaryOperator#identity()} will be used instead
   *
   * @param headersTarget where the headers will be copied to; may be
   * {@code null} in which case no action will be taken
   */
  protected static final void copyHeaders(final Map<? extends String, ? extends List<String>> headersSource,
                                          UnaryOperator<String> keyTransformer,
                                          final BiConsumer<? super String, ? super List<String>> headersTarget) {
    if (headersTarget != null && headersSource != null && !headersSource.isEmpty()) {
      final Collection<? extends Entry<? extends String, ? extends List<String>>> entrySet = headersSource.entrySet();
      if (entrySet != null && !entrySet.isEmpty()) {
        if (keyTransformer == null) {
          keyTransformer = UnaryOperator.identity();
        }
        for (final Entry<? extends String, ? extends List<String>> entry : entrySet) {
          if (entry != null) {
            headersTarget.accept(keyTransformer.apply(entry.getKey()), entry.getValue());
          }
        }
      }
    }
  }

}
