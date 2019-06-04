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
package org.microbean.jersey.netty.cdi;

import java.lang.annotation.Annotation;

import java.lang.reflect.Type;

import java.net.InetSocketAddress;

import java.nio.channels.spi.SelectorProvider;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.Executor;

import javax.enterprise.context.Dependent;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;

import javax.inject.Singleton;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.ServerBootstrap;

import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SelectStrategyFactory;

import io.netty.channel.nio.NioEventLoopGroup;

import io.netty.channel.socket.nio.NioServerSocketChannel;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory;

@Deprecated // will be moved
public class NettyExtension implements Extension {

  public NettyExtension() {
    super();
  }

  private static final void afterBeanDiscovery(@Observes final AfterBeanDiscovery event,
                                               final BeanManager beanManager) {
    // XXX TODO FIXME: this is just a stub
    final Set<? extends Annotation> qualifiers = null;
    final Annotation[] qualifiersArray = null;

    if (noBeans(beanManager, SelectorProvider.class, qualifiers)) {
      event.addBean()
        .scope(Dependent.class)
        .addTransitiveTypeClosure(SelectorProvider.class)
        .addQualifiers(qualifiersArray)
        .createWith(cc -> SelectorProvider.provider());
    }

    if (noBeans(beanManager, SelectStrategyFactory.class, qualifiers)) {
      event.addBean()
        .scope(Singleton.class)
        .addTransitiveTypeClosure(DefaultSelectStrategyFactory.class)
        .addQualifiers(qualifiersArray)
        .createWith(cc -> DefaultSelectStrategyFactory.INSTANCE);
    }

    if (noBeans(beanManager, RejectedExecutionHandler.class, qualifiers)) {
      event.addBean()
        .scope(Dependent.class) // TODO REVISIT
        .addTransitiveTypeClosure(RejectedExecutionHandler.class)
        .addQualifiers(qualifiersArray)
        .createWith(cc -> RejectedExecutionHandlers.reject());
    }

    if (noBeans(beanManager, EventLoopGroup.class, qualifiers)) {
      event.addBean()
        .scope(Dependent.class)
        .addTransitiveTypeClosure(NioEventLoopGroup.class)
        .addQualifiers(qualifiersArray)
        .produceWith(instance -> produceEventLoopGroup(beanManager, instance, qualifiersArray));
    }

    if (noBeans(beanManager, ServerBootstrap.class, qualifiers)) {
      event.addBean()
        .scope(Dependent.class)
        .addTransitiveTypeClosure(ServerBootstrap.class)
        .addQualifiers(qualifiersArray)
        .produceWith(instance -> produceServerBootstrap(beanManager, instance, qualifiersArray));
    }
    
  }

  private static final ServerBootstrap produceServerBootstrap(final BeanManager beanManager, final Instance<Object> instance, final Annotation[] qualifiersArray) {

    final Instance<NioServerSocketChannel> nioServerSocketChannelInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      nioServerSocketChannelInstance = instance.select(NioServerSocketChannel.class);
    } else {
      nioServerSocketChannelInstance = instance.select(NioServerSocketChannel.class, qualifiersArray);
    }

    final Instance<EventLoopGroup> eventLoopGroupInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      eventLoopGroupInstance = instance.select(EventLoopGroup.class);
    } else {
      eventLoopGroupInstance = instance.select(EventLoopGroup.class, qualifiersArray);
    }
    final EventLoopGroup eventLoopGroup;
    if (eventLoopGroupInstance == null || eventLoopGroupInstance.isUnsatisfied()) {
      eventLoopGroup = null;
    } else {
      eventLoopGroup = eventLoopGroupInstance.get();
    }

    // TODO: this is probably overkill; just need some config
    final Instance<InetSocketAddress> inetSocketAddressInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      inetSocketAddressInstance = instance.select(InetSocketAddress.class);
    } else {
      inetSocketAddressInstance = instance.select(InetSocketAddress.class, qualifiersArray);
    }
    final InetSocketAddress inetSocketAddress;
    if (inetSocketAddressInstance == null || inetSocketAddressInstance.isUnsatisfied()) {
      inetSocketAddress = null;
    } else {
      inetSocketAddress = inetSocketAddressInstance.get();
    }

    final ServerBootstrap returnValue = new ServerBootstrap();
    // See https://stackoverflow.com/a/28342821/208288
    returnValue.group(eventLoopGroup);
    returnValue.channelFactory(() -> nioServerSocketChannelInstance.get());
    returnValue.localAddress(inetSocketAddress);

    // TODO: channel initialization

    // Permit arbitrary customization
    beanManager.getEvent().select(ServerBootstrap.class, qualifiersArray).fire(returnValue);
    
    return returnValue;
  }
  
  private static final EventLoopGroup produceEventLoopGroup(final BeanManager beanManager, final Instance<Object> instance, final Annotation[] qualifiersArray) {
    Objects.requireNonNull(instance);

    final Instance<Executor> executorInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      executorInstance = instance.select(Executor.class);
    } else {
      executorInstance = instance.select(Executor.class, qualifiersArray);
    }
    final Executor executor;
    if (executorInstance == null || executorInstance.isUnsatisfied()) {
      executor = null;
    } else {
      executor = executorInstance.get();
    }
    
    final Instance<SelectorProvider> selectorProviderInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      selectorProviderInstance = instance.select(SelectorProvider.class);
    } else {
      selectorProviderInstance = instance.select(SelectorProvider.class, qualifiersArray);
    }
    final SelectorProvider selectorProvider;
    if (selectorProviderInstance == null || selectorProviderInstance.isUnsatisfied()) {
      selectorProvider = null;
    } else {
      selectorProvider = selectorProviderInstance.get();
    }

    final Instance<SelectStrategyFactory> selectStrategyFactoryInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      selectStrategyFactoryInstance = instance.select(SelectStrategyFactory.class);
    } else {
      selectStrategyFactoryInstance = instance.select(SelectStrategyFactory.class, qualifiersArray);
    }
    final SelectStrategyFactory selectStrategyFactory;
    if (selectStrategyFactoryInstance == null || selectStrategyFactoryInstance.isUnsatisfied()) {
      selectStrategyFactory = null;
    } else {
      selectStrategyFactory = selectStrategyFactoryInstance.get();
    }

    final Instance<EventExecutorChooserFactory> eventExecutorChooserFactoryInstance;
    if (qualifiersArray == null || qualifiersArray.length <= 0) {
      eventExecutorChooserFactoryInstance = instance.select(EventExecutorChooserFactory.class);
    } else {
      eventExecutorChooserFactoryInstance = instance.select(EventExecutorChooserFactory.class, qualifiersArray);
    }
    final EventExecutorChooserFactory eventExecutorChooserFactory;
    if (eventExecutorChooserFactoryInstance == null || eventExecutorChooserFactoryInstance.isUnsatisfied()) {
      eventExecutorChooserFactory = null;
    } else {
      eventExecutorChooserFactory = eventExecutorChooserFactoryInstance.get();
    }

    final EventLoopGroup returnValue;
    if (eventExecutorChooserFactory == null) {
      returnValue = new NioEventLoopGroup(0, executor, selectorProvider, selectStrategyFactory);
    } else {
      final Instance<RejectedExecutionHandler> rejectedExecutionHandlerInstance;
      if (qualifiersArray == null || qualifiersArray.length <= 0) {
        rejectedExecutionHandlerInstance = instance.select(RejectedExecutionHandler.class);
      } else {
        rejectedExecutionHandlerInstance = instance.select(RejectedExecutionHandler.class, qualifiersArray);
      }
      final RejectedExecutionHandler rejectedExecutionHandler;
      if (rejectedExecutionHandlerInstance == null || rejectedExecutionHandlerInstance.isUnsatisfied()) {
        rejectedExecutionHandler = null;
      } else {
        rejectedExecutionHandler = rejectedExecutionHandlerInstance.get();
      }
      returnValue = new NioEventLoopGroup(0, executor, eventExecutorChooserFactory, selectorProvider, selectStrategyFactory, rejectedExecutionHandler);
    }
    // Permit arbitrary customization
    beanManager.getEvent().select(EventLoopGroup.class, qualifiersArray).fire(returnValue);
    return returnValue;
  }

  private static final boolean noBeans(final BeanManager beanManager, final Type type) {
    return noBeans(beanManager, type, null);
  }
    
  private static final boolean noBeans(final BeanManager beanManager, final Type type, final Set<? extends Annotation> qualifiers) {
    final Collection<?> beans;
    if (beanManager != null && type != null) {
      if (qualifiers == null || qualifiers.isEmpty()) {
        beans = beanManager.getBeans(type);
      } else {
        beans = beanManager.getBeans(type, qualifiers.toArray(new Annotation[qualifiers.size()]));
      }
    } else {
      beans = null;
    }
    return beans == null || beans.isEmpty();
  }
  
}
