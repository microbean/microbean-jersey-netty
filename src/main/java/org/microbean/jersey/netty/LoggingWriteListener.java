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

import java.io.IOException;

import java.util.Objects;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

final class LoggingWriteListener implements GenericFutureListener<Future<Void>> {

  private final Logger logger;
  
  LoggingWriteListener(final Logger logger) {
    super();
    this.logger = Objects.requireNonNull(logger);
  }

  @Override
  public final void operationComplete(final Future<Void> f) {
    if (f != null) {
      final Level level;
      final Throwable cause = f.cause();
      if (cause == null) {
        level = null;
      } else if (f instanceof ChannelFuture) {
        final Channel channel = ((ChannelFuture)f).channel();
        if (channel == null) {
          level = Level.SEVERE;
        } else if (channel.isOpen()) {
          if (cause instanceof IOException) {
            final Object message = cause.getMessage();
            if ("Broken pipe".equals(message) || "Connection reset by peer".equals(message)) {
              channel.close();
              level = Level.FINE;
            } else {
              level = Level.SEVERE;
            }
          } else {
            level = Level.SEVERE;
          }
        } else {
          level = Level.FINE;
        }
      } else {
        level = Level.SEVERE;
      }
      if (level != null && logger.isLoggable(level)) {
        logger.log(level, cause.getMessage(), cause);
      }
    }
  }
  
}
