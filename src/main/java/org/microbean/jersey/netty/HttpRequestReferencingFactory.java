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

import javax.inject.Inject;
import javax.inject.Provider;

import javax.ws.rs.core.GenericType;

import io.netty.handler.codec.http.HttpRequest;

import org.glassfish.jersey.internal.inject.ReferencingFactory;

import org.glassfish.jersey.internal.util.collection.Ref;

/**
 * A {@link ReferencingFactory} for use with Jersey's odd dependency
 * injection framework.
 *
 * <p>This class participates in an overall strange idiom similar to
 * <a
 * href="https://github.com/eclipse-ee4j/jersey/blob/82a85a1bffeaf5f78607cf5c0c0d4d4e6ba0457e/containers/grizzly2-http/src/main/java/org/glassfish/jersey/grizzly2/httpserver/GrizzlyHttpContainer.java#L91-L100"
 * target="_parent">this one</a>.</p>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
final class HttpRequestReferencingFactory extends ReferencingFactory<HttpRequest> {

  static final GenericType<Ref<HttpRequest>> genericRefType = new GenericType<Ref<HttpRequest>>() {};
  
  @Inject
  private HttpRequestReferencingFactory(final Provider<Ref<HttpRequest>> provider) {
    super(provider);
  }
  
}
