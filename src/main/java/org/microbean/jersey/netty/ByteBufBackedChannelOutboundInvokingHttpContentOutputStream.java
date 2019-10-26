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

import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelOutboundInvoker;

import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;

/**
 * A {@link
 * ByteBufBackedChannelOutboundInvokingHttpContentOutputStream} that
 * writes {@link HttpContent} messages.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see #createMessage(ByteBuf)
 *
 * @see #createLastMessage()
 */
public final class ByteBufBackedChannelOutboundInvokingHttpContentOutputStream extends ByteBufBackedChannelOutboundInvokingOutputStream<HttpContent> {

  /**
   * Creates a new {@link ByteBufBackedChannelOutboundInvokingHttpContentOutputStream}.
   *
   * @param channelOutboundInvoker {@inheritDoc}
   *
   * @param closeChannelOutboundInvoker {@inheritDoc}
   */
  public ByteBufBackedChannelOutboundInvokingHttpContentOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                                     final boolean closeChannelOutboundInvoker) {
    super(channelOutboundInvoker, closeChannelOutboundInvoker);
  }
  
  public ByteBufBackedChannelOutboundInvokingHttpContentOutputStream(final ChannelOutboundInvoker channelOutboundInvoker,
                                                                     final int flushThreshold,
                                                                     final boolean closeChannelOutboundInvoker,
                                                                     final ByteBufBackedChannelOutboundInvokingOutputStream.ByteBufCreator byteBufCreator) {
    super(channelOutboundInvoker, flushThreshold, closeChannelOutboundInvoker, byteBufCreator);
  }

  @Override
  protected final HttpContent createLastMessage() {
    return new DefaultLastHttpContent();
  }
  
  @Override
  protected final HttpContent createMessage(final ByteBuf content) {
    return new DefaultHttpContent(content);
  }
  
}
