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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;

import io.netty.channel.ChannelHandlerContext;

import io.netty.handler.codec.MessageToMessageDecoder;

import org.glassfish.jersey.internal.PropertiesDelegate;

import org.glassfish.jersey.server.ContainerRequest;

import org.glassfish.jersey.server.internal.ContainerUtils;

/**
 * A {@link MessageToMessageDecoder} that decodes messages of a
 * specific type into {@link ContainerRequest}s.
 *
 * @param <T> the common supertype of messages that can be decoded
 *
 * @param <H> the type of {@linkplain #isHeaders(Object) "headers" messages}
 *
 * @param <D> the type of {@linkplain #isData(Object) "data" messages}
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #decode(ChannelHandlerContext, Object, List)
 *
 * @see MessageToMessageDecoder
 *
 * @see ContainerRequest
 */
public abstract class AbstractContainerRequestDecoder<T, H extends T, D extends T> extends MessageToMessageDecoder<T> {


  /*
   * Static fields.
   */

  
  private static final String cn = AbstractContainerRequestDecoder.class.getName();
  
  private static final Logger logger = Logger.getLogger(cn);


  /*
   * Instance fields.
   */

  private final Class<H> headersClass;

  private final Class<D> dataClass;
  
  private final URI baseUri;

  private TerminableByteBufInputStream terminableByteBufInputStream;
  
  private ContainerRequest containerRequestUnderConstruction;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link AbstractContainerRequestDecoder} implementation.
   *
   * @param baseUri the base {@link URI} against which relative
   * request URIs will be resolved; may be {@code null} in which case
   * the return value of {@link URI#create(String) URI.create("/")}
   * will be used instead
   *
   * @param headersClass the type representing a "headers" message;
   * must not be {@code null}
   *
   * @param dataClass the type representing a "data" message; must not
   * be {@code null}
   *
   * @exception NullPointerException if either {@code headersClass} or
   * {@code dataClass} is {@code null}
   */
  protected AbstractContainerRequestDecoder(final URI baseUri,
                                            final Class<H> headersClass,
                                            final Class<D> dataClass) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
    this.headersClass = Objects.requireNonNull(headersClass);
    this.dataClass = Objects.requireNonNull(dataClass);
  }


  /*
   * Instance methods.
   */
  

  @Override
  public boolean acceptInboundMessage(final Object message) {
    return this.headersClass.isInstance(message) || this.dataClass.isInstance(message);
  }

  /**
   * Returns {@code true} if the supplied message represents a
   * "headers" message (as distinguished from a "data" message).
   *
   * @param message the message to interrogate; will not be {@code
   * null}
   *
   * @return {@code true} if the supplied message represents a
   * "headers" message; {@code false} otherwise
   *
   * @see #isData(Object)
   */
  protected boolean isHeaders(final T message) {
    return this.headersClass.isInstance(message);
  }

  /**
   * Extracts and returns a {@link String} representing a request URI
   * from the supplied message, which is guaranteed to be a
   * {@linkplain #isHeaders(Object) "headers" message}.
   *
   * <p>Implementations of this method may return {@code null} but
   * normally will not.</p>
   *
   * @param message the message to interrogate; will not be {@code
   * null}
   *
   * @return a {@link String} representing a request URI from the
   * supplied message, or {@code null}
   */
  protected abstract String getRequestUriString(final H message);

  /**
   * Extracts and returns a {@link String} representing a request
   * method from the supplied message, which is guaranteed to be a
   * {@linkplain #isHeaders(Object) "headers" message}.
   *
   * <p>Implementations of this method may return {@code null} but
   * normally will not.</p>
   *
   * @param message the message to interrogate; will not be {@code
   * null}
   *
   * @return a {@link String} representing a request method from the
   * supplied message, or {@code null}
   */
  protected abstract String getMethod(final H message);

  /**
   * Creates and returns a {@link SecurityContext} appropriate for the
   * supplied message, which is guaranteed to be a {@linkplain
   * #isHeaders(Object) "headers" message}.
   *
   * <p>Implementations of this method must not return {@code
   * null}.</p>
   *
   * @param message the {@linkplain #isHeaders(Object) "headers"
   * message} for which a new {@link SecurityContext} is to be
   * returned; will not be {@code null}
   *
   * @return a new, non-{@code null} {@link SecurityContext}
   */
  protected SecurityContext createSecurityContext(final H message) {
    return new SecurityContextAdapter();
  }

  /**
   * Creates and returns a {@link PropertiesDelegate} appropriate for the
   * supplied message, which is guaranteed to be a {@linkplain
   * #isHeaders(Object) "headers" message}.
   *
   * <p>Implementations of this method must not return {@code
   * null}.</p>
   *
   * @param message the {@linkplain #isHeaders(Object) "headers"
   * message} for which a new {@link PropertiesDelegate} is to be
   * returned; will not be {@code null}
   *
   * @return a new, non-{@code null} {@link PropertiesDelegate}
   */
  protected PropertiesDelegate createPropertiesDelegate(final H message) {
    return new MapBackedPropertiesDelegate();
  }

  /**
   * Installs the supplied {@code message} into the supplied {@link
   * ContainerRequest}.
   *
   * <p>This implementation calls {@link
   * ContainerRequest#setProperty(String, Object)} with the
   * {@linkplain Class#getName() fully-qualified class name} of the
   * headers class supplied at construction time as the key, and the
   * supplied {@code message} as the value.</p>
   *
   * @param message the message to install; will not be {@code null}
   * and is guaranteed to be a {@linkplain #isHeaders(Object)
   * "headers" message}
   *
   * @param containerRequest the just-constructed {@link
   * ContainerRequest} into which to install the supplied {@code
   * message}; will not be {@code null}
   */
  protected void installMessage(final H message, final ContainerRequest containerRequest) {
    containerRequest.setProperty(this.headersClass.getName(), message);
  }

  /**
   * Returns {@code true} if the supplied message represents a
   * "data" message (as distinguished from a "headers" message).
   *
   * @param message the message to interrogate; will not be {@code
   * null}
   *
   * @return {@code true} if the supplied message represents a
   * "data" message; {@code false} otherwise
   *
   * @see #isHeaders(Object)
   */
  protected boolean isData(final T message) {
    return this.dataClass.isInstance(message);
  }

  /**
   * Extracts any content from the supplied {@linkplain
   * #isData(Object) "data" message} as a {@link ByteBuf}, or returns
   * {@code null} if there is no such content.
   *
   * @param message the {@linkplain #isData(Object) "data" message}
   * from which a {@link ByteBuf} is to be extracted; will not be
   * {@code null}
   *
   * @return a {@link ByteBuf} representing the message's content, or
   * {@code null}
   */
  protected ByteBuf getContent(final D message) {
    final ByteBuf returnValue;
    if (message instanceof ByteBuf) {
      returnValue = (ByteBuf)message;
    } else if (message instanceof ByteBufHolder) {
      returnValue = ((ByteBufHolder)message).content();
    } else {
      returnValue = null;
    }
    return returnValue;
  }

  /**
   * Returns {@code true} if there will be no further message
   * components in an overall larger message after the supplied one.
   *
   * @param message the message component to interrogate; will not be {@code
   * null}; may be either a {@linkplain #isHeaders(Object) "headers"}
   * or {@linkplain #isData(Object) "data"} message component
   *
   * @return {@code true} if and only if there will be no further
   * message components to come
   */
  protected abstract boolean isLast(final T message);

  /**
   * Decodes the supplied {@code message} into a {@link
   * ContainerRequest} and adds it to the supplied {@code out} {@link
   * List}.
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; must not be {@code null}
   *
   * @param message the message to decode; must not be {@code null}
   * and must be {@linkplain #acceptInboundMessage(Object) acceptable}
   *
   * @param out a {@link List} of {@link Object}s that result from
   * decoding the supplied {@code message}; must not be {@code null}
   * and must be mutable
   *
   * @exception NullPointerException if {@code channelHandlerContext}
   * or {@code out} is {@code null}
   *
   * @exception IllegalArgumentException if {@code message} is {@code
   * null} or otherwise unacceptable
   *
   * @exception IllegalStateException if there is an internal problem
   * with state management
   */
  @Override
  protected final void decode(final ChannelHandlerContext channelHandlerContext,
                              final T message,
                              final List<Object> out) {
    if (isHeaders(message)) {
      if (this.containerRequestUnderConstruction == null) {
        if (this.terminableByteBufInputStream == null) {
          final URI requestUri;
          final H headersMessage = this.headersClass.cast(message);
          final String requestUriString = this.getRequestUriString(headersMessage);
          if (requestUriString == null) {
            requestUri = this.baseUri;
          } else if (requestUriString.startsWith("/") && requestUriString.length() > 1) {
            requestUri = this.baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(requestUriString.substring(1)));
          } else {
            requestUri = this.baseUri.resolve(ContainerUtils.encodeUnsafeCharacters(requestUriString));
          }
          final String method = this.getMethod(headersMessage);
          final SecurityContext securityContext = this.createSecurityContext(headersMessage);
          final PropertiesDelegate propertiesDelegate = this.createPropertiesDelegate(headersMessage);
          final ContainerRequest containerRequest =
            new ContainerRequest(this.baseUri,
                                 requestUri,
                                 method,
                                 securityContext == null ? new SecurityContextAdapter() : securityContext,
                                 propertiesDelegate == null ? new MapBackedPropertiesDelegate() : propertiesDelegate);
          this.installMessage(headersMessage, containerRequest);
          if (this.isLast(message)) {
            out.add(containerRequest);
          } else {
            this.containerRequestUnderConstruction = containerRequest;
          }
        } else {
          throw new IllegalStateException("this.terminableByteBufInputStream != null: " + this.terminableByteBufInputStream);
        }
      } else {
        throw new IllegalStateException("this.containerRequestUnderConstruction != null: " + this.containerRequestUnderConstruction);
      }
    } else if (this.isData(message)) {
      final D dataMessage = this.dataClass.cast(message);
      final ByteBuf content = this.getContent(dataMessage);
      if (content == null || content.readableBytes() <= 0) {
        if (this.isLast(message)) {
          if (this.containerRequestUnderConstruction == null) {
            if (this.terminableByteBufInputStream != null) {
              throw new IllegalStateException("this.containerRequestUnderConstruction == null && this.terminableByteBufInputStream != null: " + this.terminableByteBufInputStream);
            }
          } else {
            out.add(this.containerRequestUnderConstruction);
            this.containerRequestUnderConstruction = null;
          }
          if (this.terminableByteBufInputStream != null) {
            this.terminableByteBufInputStream.terminate();
            this.terminableByteBufInputStream = null;
          }
        } else if (this.containerRequestUnderConstruction == null) {
          throw new IllegalStateException("this.containerRequestUnderConstruction == null");
        } else {
          // We got an empty chunk in the middle for some reason; just skip it
        }
      } else if (this.containerRequestUnderConstruction == null) {
        throw new IllegalStateException("this.containerRequestUnderConstruction == null");
      } else if (this.isLast(message)) {
        final TerminableByteBufInputStream terminableByteBufInputStream;
        if (this.terminableByteBufInputStream == null) {
          final TerminableByteBufInputStream newlyCreatedTerminableByteBufInputStream = this.createTerminableByteBufInputStream(channelHandlerContext.alloc());
          this.containerRequestUnderConstruction.setEntityStream(newlyCreatedTerminableByteBufInputStream);
          out.add(this.containerRequestUnderConstruction);
          terminableByteBufInputStream = newlyCreatedTerminableByteBufInputStream;
        } else {
          terminableByteBufInputStream = this.terminableByteBufInputStream;
          this.terminableByteBufInputStream = null;
        }
        assert this.terminableByteBufInputStream == null;
        assert terminableByteBufInputStream != null;
        terminableByteBufInputStream.addByteBuf(content);
        terminableByteBufInputStream.terminate();
        this.containerRequestUnderConstruction = null;
      } else {
        if (this.terminableByteBufInputStream == null) {
          final TerminableByteBufInputStream newlyCreatedTerminableByteBufInputStream = this.createTerminableByteBufInputStream(channelHandlerContext.alloc());
          this.terminableByteBufInputStream = newlyCreatedTerminableByteBufInputStream;
          this.containerRequestUnderConstruction.setEntityStream(newlyCreatedTerminableByteBufInputStream);
          out.add(this.containerRequestUnderConstruction);
        }
        this.terminableByteBufInputStream.addByteBuf(content);
      }
    } else {
      throw new IllegalArgumentException("Unexpected message: " + message);
    }
  }

  protected TerminableByteBufInputStream createTerminableByteBufInputStream(final ByteBufAllocator byteBufAllocator) {
    return new TerminableByteBufInputStream(byteBufAllocator);
  }
  
}
