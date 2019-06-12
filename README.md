# microBean™ Jersey Netty Integration

[![Build Status](https://travis-ci.com/microbean/microbean-jersey-netty.svg?branch=master)](https://travis-ci.com/microbean/microbean-jersey-netty)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jersey-netty/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jersey-netty)

The microBean™ Jersey Netty Integration project integrates [Jersey](https://jersey.github.io/) into
[Netty](https://netty.io) in an idiomatic way.

Jersey can be run as a simple handler of sorts in a native Netty
pipeline.  The Netty event loop remains unblocked, there is no
locking, reads and writes involve no copying of byte arrays, output is
streamed where appropriate and there are very few object allocations.

Jersey itself contains a [Netty integration
project](https://github.com/eclipse-ee4j/jersey/tree/master/containers/netty-http),
but it is annotated with
[`@Beta`](https://jersey.github.io/apidocs/2.28/jersey/org/glassfish/jersey/Beta.html),
and the author additionally writes:

> Note that this implementation cannot be more experimental.

There are several issues with this "native" Netty integration project.
The most problematic seems to be [issue
3500](https://github.com/eclipse-ee4j/jersey/issues/3500).  This issue
and others stem from the fact that the "native" Netty integration
project sets up its own internal queues for streaming, which overflow.
Additionally, new `ByteBuffer`s are allocated throughout.

This implementation instead shares a
[`ByteBuf`](https://netty.io/4.1/api/io/netty/buffer/ByteBuf.html) for
reading and writing, and makes heavy use of Netty's
[`ChunkedWriteHandler`](https://netty.io/4.1/api/io/netty/handler/stream/ChunkedWriteHandler.html),
while also ultimately ensuring that all operations on a given
`ByteBuf` that originate from Jersey are serialized to the Netty event
loop.  This dramatically reduces object allocations, locks, threading
issues, exception handling pathways and other concurrency problems.
