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

import java.lang.reflect.Type;

import java.net.URI;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import java.util.function.Supplier;

import java.util.logging.Logger;

import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.SecurityContext;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;

import io.netty.channel.ChannelConfig; // for javadoc only
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter; // for javadoc only
import io.netty.channel.ChannelPipeline; // for javadoc only

import io.netty.handler.codec.MessageToMessageDecoder;

import org.glassfish.jersey.internal.PropertiesDelegate;

import org.glassfish.jersey.internal.util.collection.Ref;

import org.glassfish.jersey.server.ContainerRequest;

import org.glassfish.jersey.server.internal.ContainerUtils;

/**
 * A {@link MessageToMessageDecoder} that decodes messages of a
 * specific type into {@link ContainerRequest}s.
 *
 * <p>Instances of this class are normally followed in a {@link
 * ChannelPipeline} by instances of the {@link
 * AbstractContainerRequestHandlingResponseWriter} class.</p>
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
 *
 * @see AbstractContainerRequestHandlingResponseWriter
 */
public abstract class AbstractContainerRequestDecoder<T, H extends T, D extends T> extends MessageToMessageDecoder<T> {


  /*
   * Static fields.
   */


  private static final String cn = AbstractContainerRequestDecoder.class.getName();

  private static final Logger logger = Logger.getLogger(cn);

  private static final Type channelHandlerContextRefType = ChannelHandlerContextReferencingFactory.genericRefType.getType();


  /*
   * Instance fields.
   */


  private final Class<H> headersClass;

  private final Type headersClassRefType;

  private final Class<D> dataClass;

  private final URI baseUri;

  private final Supplier<? extends Configuration> configurationSupplier;

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
   *
   * @see #AbstractContainerRequestDecoder(URI, Configuration, Class,
   * Class)
   *
   * @deprecated Please use the {@link
   * #AbstractContainerRequestDecoder(URI, Configuration, Class,
   * Class)} constructor instead.
   */
  @Deprecated
  protected AbstractContainerRequestDecoder(final URI baseUri,
                                            final Class<H> headersClass,
                                            final Class<D> dataClass) {
    this(baseUri, AbstractContainerRequestDecoder::returnNull, headersClass, dataClass);
  }

  /**
   * Creates a new {@link AbstractContainerRequestDecoder} implementation.
   *
   * @param baseUri the base {@link URI} against which relative
   * request URIs will be resolved; may be {@code null} in which case
   * the return value of {@link URI#create(String) URI.create("/")}
   * will be used instead
   *
   * @param configuration a {@link Configuration} describing how the
   * container is configured; may be {@code null}
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
                                            final Configuration configuration,
                                            final Class<H> headersClass,
                                            final Class<D> dataClass) {
    this(baseUri, configuration == null ? AbstractContainerRequestDecoder::returnNull : new ImmutableSupplier<>(configuration), headersClass, dataClass);
  }

  /**
   * Creates a new {@link AbstractContainerRequestDecoder} implementation.
   *
   * @param baseUri the base {@link URI} against which relative
   * request URIs will be resolved; may be {@code null} in which case
   * the return value of {@link URI#create(String) URI.create("/")}
   * will be used instead
   *
   * @param configurationSupplier a {@link Supplier} of {@link
   * Configuration} instances describing how the container is
   * configured; may be {@code null}
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
                                            final Supplier<? extends Configuration> configurationSupplier,
                                            final Class<H> headersClass,
                                            final Class<D> dataClass) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
    if (configurationSupplier == null) {
      this.configurationSupplier = AbstractContainerRequestDecoder::returnNull;
    } else {
      this.configurationSupplier = configurationSupplier;
    }
    this.headersClass = Objects.requireNonNull(headersClass);
    this.headersClassRefType = new ParameterizedType(Ref.class, headersClass);
    this.dataClass = Objects.requireNonNull(dataClass);
  }


  /*
   * Instance methods.
   */


  /**
   * Overrides the {@link
   * ChannelInboundHandlerAdapter#channelReadComplete(ChannelHandlerContext)}
   * method to {@linkplain ChannelHandlerContext#read() request a
   * read} when necessary, taking {@linkplain
   * ChannelConfig#isAutoRead() the auto-read status of the associated
   * <code>Channel</code>} into account.
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; must not be {@code null}
   *
   * @exception NullPointerException if {@code channelHandlerContext}
   * is {@code null}
   *
   * @see ChannelConfig#isAutoRead()
   *
   * @see
   * ChannelInboundHandlerAdapter#channelReadComplete(ChannelHandlerContext)
   */
  @Override
  public void channelReadComplete(final ChannelHandlerContext channelHandlerContext)
    throws Exception {
    super.channelReadComplete(channelHandlerContext);
    if (this.containerRequestUnderConstruction != null &&
        !channelHandlerContext.channel().config().isAutoRead()) {
      channelHandlerContext.read();
    }
  }

  /**
   * Returns {@code true} if the supplied {@code message} is an
   * instance of either the {@linkplain
   * #AbstractContainerRequestDecoder(URI, Class, Class) headers type
   * or data type supplied at construction time}, and {@code false} in
   * all other cases.
   *
   * @param message the message to interrogate; may be {@code null} in
   * which case {@code false} will be returned
   *
   * @return {@code true} if the supplied {@code message} is an
   * instance of either the {@linkplain
   * #AbstractContainerRequestDecoder(URI, Class, Class) headers type
   * or data type supplied at construction time}; {@code false} in all
   * other cases
   *
   * @see #AbstractContainerRequestDecoder(URI, Class, Class)
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
   * ContainerRequest} in some way.
   *
   * <p>This implementation calls {@link
   * ContainerRequest#setProperty(String, Object)} with the
   * {@linkplain Class#getName() fully-qualified class name} of the
   * headers class supplied at construction time as the key, and the
   * supplied {@code message} as the value.</p>
   *
   * @param channelHandlerContext the {@link ChannelHandlerContext} in
   * effect; will not be {@code null}; supplied for convenience;
   * overrides may (and often do) ignore this parameter
   *
   * @param message the message to install; will not be {@code null}
   * and is guaranteed to be a {@linkplain #isHeaders(Object)
   * "headers" message}
   *
   * @param containerRequest the just-constructed {@link
   * ContainerRequest} into which to install the supplied {@code
   * message}; will not be {@code null}
   */
  protected void installMessage(final ChannelHandlerContext channelHandlerContext,
                                final H message,
                                final ContainerRequest containerRequest) {
    containerRequest.setProperty(ChannelHandlerContext.class.getName(), channelHandlerContext);
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
                                 propertiesDelegate == null ? new MapBackedPropertiesDelegate() : propertiesDelegate,
                                 this.configurationSupplier.get());
          this.installMessage(channelHandlerContext, headersMessage, containerRequest);
          containerRequest.setRequestScopedInitializer(injectionManager -> {
              // See JerseyChannelInitializer, where the factories of
              // factories that produce references of the things we're
              // interested in are installed.  Here, in request scope
              // itself, we set the actual target of those references.
              // This is apparently the proper way to do this sort of
              // thing in Jersey (!) and examples of this pattern show
              // up throughout its codebase.  With jaw somewhat agape,
              // we follow suit.
              final Ref<ChannelHandlerContext> channelHandlerContextRef = injectionManager.getInstance(channelHandlerContextRefType);
              if (channelHandlerContextRef != null) {
                channelHandlerContextRef.set(channelHandlerContext);
              }
              final Ref<H> headersRef = injectionManager.getInstance(this.headersClassRefType);
              if (headersRef != null) {
                headersRef.set(headersMessage);
              }
            });
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
          if (this.terminableByteBufInputStream == null) {
            if (this.containerRequestUnderConstruction == null) {
              // Do nothing; this is a final, zero-content message and
              // we already dealt with the previous headers message
              // component.
            } else {
              out.add(this.containerRequestUnderConstruction);
              this.containerRequestUnderConstruction = null;
            }
          } else if (this.containerRequestUnderConstruction == null) {
            throw new IllegalStateException("this.containerRequestUnderConstruction == null && this.terminableByteBufInputStream != null: " + this.terminableByteBufInputStream);
          } else {
            out.add(this.containerRequestUnderConstruction);
            this.containerRequestUnderConstruction = null;
            this.terminableByteBufInputStream.terminate();
            this.terminableByteBufInputStream = null;
          }
        } else if (this.containerRequestUnderConstruction == null) {
          throw new IllegalStateException("this.containerRequestUnderConstruction == null");
        } else {
          // We got an empty chunk in the middle of the stream.
          // Ignore it.  Note that
          // #channelReadComplete(ChannelHandlerContext) will take
          // care of auto-read-or-not situations.
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
        content.retain(); // see https://github.com/microbean/microbean-jersey-netty/issues/12
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
        content.retain(); // see https://github.com/microbean/microbean-jersey-netty/issues/12
        this.terminableByteBufInputStream.addByteBuf(content);
      }
    } else {
      throw new IllegalArgumentException("Unexpected message: " + message);
    }
  }

  /**
   * Creates and returns a new {@link TerminableByteBufInputStream}.
   *
   * @param byteBufAllocator a {@link ByteBufAllocator} that may be
   * used or ignored; will not be {@code null}
   *
   * @return a new, non-{@code null} {@link
   * TerminableByteBufInputStream}
   */
  protected TerminableByteBufInputStream createTerminableByteBufInputStream(final ByteBufAllocator byteBufAllocator) {
    return new TerminableByteBufInputStream(byteBufAllocator);
  }


  /*
   * Static methods.
   */


  /**
   * Returns {@code null} when invoked.
   *
   * <p>This method is used only via method reference and only in
   * pathological cases.</p>
   *
   * @return {@code null} in all cases
   */
  private static final Configuration returnNull() {
    return null;
  }


  /*
   * Inner and nested classes.
   */


  private static final class ParameterizedType implements java.lang.reflect.ParameterizedType {

    private final Type rawType;

    private final Type[] actualTypeArguments;

    private ParameterizedType(final Type rawType, final Type... actualTypeArguments) {
      super();
      this.rawType = rawType;
      this.actualTypeArguments = actualTypeArguments;
    }

    @Override
    public final Type[] getActualTypeArguments() {
      return this.actualTypeArguments;
    }

    @Override
    public final Type getRawType() {
      return this.rawType;
    }

    @Override
    public final Type getOwnerType() {
      return null;
    }

    @Override
    public int hashCode() {
      final Object rawType = this.getRawType();
      final int actualTypeArgumentsHashCode = Arrays.hashCode(this.getActualTypeArguments());
      return rawType == null ? actualTypeArgumentsHashCode : actualTypeArgumentsHashCode ^ rawType.hashCode();
    }

    @Override
    public final boolean equals(final Object other) {
      if (other == this) {
        return true;
      } else if (other instanceof java.lang.reflect.ParameterizedType) {
        final java.lang.reflect.ParameterizedType her = (java.lang.reflect.ParameterizedType)other;

        final Object rawType = this.getRawType();
        if (rawType == null) {
          if (her.getRawType() != null) {
            return false;
          }
        } else if (!rawType.equals(her.getRawType())) {
          return false;
        }

        final Object[] actualTypeArguments = this.getActualTypeArguments();
        if (actualTypeArguments == null) {
          if (her.getActualTypeArguments() != null) {
            return false;
          }
        } else if (!Arrays.equals(actualTypeArguments, her.getActualTypeArguments())) {
          return false;
        }

        return true;
      } else {
        return false;
      }
    }

  }

}
