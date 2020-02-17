# microBean™ Jersey Netty Integration

[![Build Status](https://travis-ci.com/microbean/microbean-jersey-netty.svg?branch=master)](https://travis-ci.com/microbean/microbean-jersey-netty)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jersey-netty/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jersey-netty)

The microBean™ Jersey Netty Integration project integrates
[Jersey](https://jersey.github.io/) into [Netty](https://netty.io) in
an idiomatic way.

Jersey can be run as a simple handler of sorts in a [Netty
pipeline](http://tutorials.jenkov.com/netty/netty-channelpipeline.html).
The Netty event loop remains unblocked, there is no locking, reads and
writes involve no copying of byte arrays, output is streamed where
appropriate and it is intended that there be as few object allocations
as possible.

HTTP 1.1 and HTTP/2 are both supported, including upgrades via [HTTP's
upgrade
header](https://svn.tools.ietf.org/svn/wg/httpbis/specs/rfc7230.html#header.upgrade),
[ALPN](https://www.rfc-editor.org/rfc/rfc7301#page-2) or [prior
knowledge](https://http2.github.io/http2-spec/#known-http).

## Status

This project uses [semantic versioning](https://semver.org/).  It is
still in an alpha state.

## Installation

Add a dependency on this project in your Netty-based Maven project:

```
<dependency>
  <groupId>org.microbean</groupId>
  <artifactId>microbean-jersey-netty</artifactId>
  <version>0.24.0</version>
</dependency>
```

## Usage

To use, install an instance of
[`JerseyChannelInitializer`](https://microbean.github.io/microbean-jersey-netty/apidocs/org/microbean/jersey/netty/JerseyChannelInitializer.html)
as the [child
handler](https://netty.io/4.1/api/io/netty/bootstrap/ServerBootstrap.html#childHandler-io.netty.channel.ChannelHandler-)
of a Netty
[`ServerBootstrap`](https://netty.io/4.1/api/io/netty/bootstrap/ServerBootstrap.html):

    serverBootstrap.childHandler(new JerseyChannelInitializer(baseUri, // e.g. URI.create("/")
        sslContext, // an SslContext, or null if you don't want TLS support
        true, // yes, HTTP/2 support please
        20971520L, // 20MB maximum incoming payload (arbitrary)
        new DefaultEventExecutorGroup(8), // a DefaultEventExecutorGroup with 8 threads (arbitrary) to run your application
        true, // yes, use Jersey's native dependency injection facilities
        new ApplicationHandler(yourJaxRsApplication), // the ApplicationHandler wrapping your application
        8192, // write in 8K chunks (arbitrary)
        Unpooled::new /* how to create those chunks */));

## Background and Motivation

While Jersey itself contains a [Netty integration
project](https://github.com/eclipse-ee4j/jersey/tree/master/containers/netty-http),
it is annotated with
[`@Beta`](https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/Beta.html),
and the author additionally
[writes](https://github.com/eclipse-ee4j/jersey/blob/8dcfed4836d26c57ef78be68214d186e9ca78b84/containers/netty-http/src/main/java/org/glassfish/jersey/netty/httpserver/JerseyHttp2ServerHandler.java#L50)
that his "implementation cannot be more experimental".  In addition,
there are several issues with the Jersey-supplied Netty integration
project.  The most problematic seems to be [issue
3500](https://github.com/eclipse-ee4j/jersey/issues/3500).  This issue
and others stem from the fact that the Jersey-supplied Netty
integration project sets up its own internal queues for streaming,
which overflow.  Additionally, new `ByteBuffer`s and `InputStream`s
are allocated throughout.  It is also not entirely clear if HTTP/2 is
fully supported.

Recently, [there have been some efforts to improve this
area](https://github.com/eclipse-ee4j/jersey/commit/8dcfed4836d26c57ef78be68214d186e9ca78b84),
but they still do not take advantage of the built in queuing and
threading constructs offered up by Netty itself.

## Implementation Details

By contrast, microBean™ Jersey Netty Integration approaches the
problem of running a Jakarta RESTful Web Services application under
Netty by following the spirit of Netty.

Several composable channel pipeline components are provided.

Considering HTTP 1.1 support, the first is a
[_decoder_](https://netty.io/4.1/api/io/netty/handler/codec/MessageToMessageDecoder.html)
that decodes
[`HttpRequest`](https://netty.io/4.1/api/io/netty/handler/codec/http/HttpRequest.html)
and
[`HttpContent`](https://netty.io/4.1/api/io/netty/handler/codec/http/HttpContent.html)
messages into
[`ContainerRequest`](https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/server/ContainerRequest.html)
objects, which are the objects consumed natively by Jersey.

A
[`ContainerRequest`](https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/server/ContainerRequest.html)
may need an [entity
stream](https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/server/ContainerRequest.html#setEntityStream-java.io.InputStream-),
and that can be tricky.  Since a Jakarta RESTful Web Services
application may block whatever thread it is running on for some time
(for example, perhaps it is performing synchronous database access
over a slow connection), then normally it should be run on its own
thread that is not the Netty event loop.  But assuming that this is
so, the `InputStream` it will receive by way of a
[`ContainerRequest`'s entity
stream](https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/server/ContainerRequest.html#setEntityStream-java.io.InputStream-)
will now be operated on by two threads: the application-hosting
thread, and the Netty event loop.  microBean™ Jersey Netty Integration
ensures that [this `InputStream`
implementation](https://microbean.github.io/microbean-jersey-netty/apidocs/org/microbean/jersey/netty/TerminableByteBufInputStream.html)
is thread-safe, uses as few locks as possible, allocates as little
memory as possible, and takes advantage of
[`CompositeByteBuf`](https://netty.io/4.1/api/io/netty/buffer/CompositeByteBuf.html)
and other Netty native constructs to ensure this inter-thread
messaging is safe and efficient.

Similarly, a `ContainerRequest` needs a
[`ContainerResponseWriter`](https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/server/spi/ContainerResponseWriter.html#writeResponseStatusAndHeaders-long-org.glassfish.jersey.server.ContainerResponse-)
which is responsible for actually writing the Jakarta RESTful Web
Services application's output.  microBean™ Jersey Netty Integration
provides a `ContainerResponseWriter` implementation that is also a
[`ChannelInboundHandlerAdapter`](https://netty.io/4.1/api/io/netty/channel/ChannelInboundHandlerAdapter.html):
it takes `ContainerRequest`s as input, installs itself as their
`ContainerResponseWriter` implementation, and writes the requisite
output back to the channel safely and efficiently.

The `OutputStream` implementation used to do this must, of course,
ensure that writes take place on the Netty event loop.  microBean™
Jersey Netty Integration ensures that [this `OutputStream`
implementation](https://microbean.github.io/microbean-jersey-netty/apidocs/org/microbean/jersey/netty/ByteBufBackedChannelOutboundInvokingHttpContentOutputStream.html)
does not needlessly buffer and/or copy `byte` arrays but instead takes
advantage of the built-in outbound event queuing present in the Netty
event loop itself.

Similar constructs are provided for HTTP/2 support as well.

