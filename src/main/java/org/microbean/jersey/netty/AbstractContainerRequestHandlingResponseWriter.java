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

import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerException;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;

import org.glassfish.jersey.server.spi.ContainerResponseWriter;
import org.glassfish.jersey.server.spi.ContainerResponseWriter.TimeoutHandler;

public abstract class AbstractContainerRequestHandlingResponseWriter extends ChannelInboundHandlerAdapter implements ContainerResponseWriter {


  /*
   * Static fields.
   */


  private static final String cn = AbstractContainerRequestHandlingResponseWriter.class.getName();

  private static final Logger logger = Logger.getLogger(cn);


  /*
   * Instance fields.
   */


  /**
   * The {@link ApplicationHandler} that represents Jersey.
   *
   * <p>This field is never {@code null}.</p>
   */
  private final ApplicationHandler applicationHandler;

  private ScheduledFuture<?> suspendTimeoutFuture;

  private Runnable suspendTimeoutHandler;

  private ChannelHandlerContext channelHandlerContext;


  /*
   * Constructors.
   */


  protected AbstractContainerRequestHandlingResponseWriter(final ApplicationHandler applicationHandler) {
    super();
    this.applicationHandler = applicationHandler == null ? new ApplicationHandler() : applicationHandler;
  }


  /*
   * Instance methods.
   */


  @Override
  public final void channelRead(final ChannelHandlerContext channelHandlerContext,
                                final Object message)
    throws Exception {
    this.channelHandlerContext = Objects.requireNonNull(channelHandlerContext);
    try {
      if (message instanceof ContainerRequest) {
        final ContainerRequest containerRequest = (ContainerRequest)message;
        containerRequest.setWriter(this);
        this.applicationHandler.handle(containerRequest);
      } else {
        super.channelRead(channelHandlerContext, message);
      }
    } finally {
      this.channelHandlerContext = null;
    }
  }

  @Override
  public final void channelReadComplete(final ChannelHandlerContext channelHandlerContext) {
    Objects.requireNonNull(channelHandlerContext).flush();
  }

  protected final ChannelHandlerContext getChannelHandlerContext() {
    return this.channelHandlerContext;
  }

  /*
   * ContainerResponseWriter overrides.
   */

  /**
   * Returns {@code true} when invoked.
   *
   * <p>Note that this is a default value.  Response buffering <a
   * href="https://github.com/eclipse-ee4j/jersey/blob/a40169547a602a582f5fed1fd8ebe595ff2b83f7/core-common/src/main/java/org/glassfish/jersey/message/internal/OutboundMessageContext.java#L761-L778"
   * target="_parent">can be configured</a>.</p>
   *
   * @return {@code true} when invoked
   *
   * @see ContainerResponseWriter#enableResponseBuffering()
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
  public boolean enableResponseBuffering() {
    return true;
  }

  @Override
  public final OutputStream writeResponseStatusAndHeaders(final long contentLength,
                                                          final ContainerResponse containerResponse) {
    final OutputStream returnValue;
    if (this.writeStatusAndHeaders(contentLength, Objects.requireNonNull(containerResponse))) {
      returnValue = this.createOutputStream(contentLength, containerResponse);
    } else {
      returnValue = null;
    }
    return returnValue;
  }

  protected abstract boolean writeStatusAndHeaders(final long contentLength,
                                                   final ContainerResponse containerResponse);

  protected abstract OutputStream createOutputStream(final long contentLength,
                                                     final ContainerResponse containerResponse);

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
        // TODO: not sure about this
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
      throw new IllegalStateException();
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
