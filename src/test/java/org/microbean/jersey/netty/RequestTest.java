package org.microbean.jersey.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.glassfish.jersey.server.ApplicationHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestTest {

    private static NioEventLoopGroup group;
    private static WebTarget webTarget;

    @BeforeAll
    static void beforeAll() throws InterruptedException {
        group = new NioEventLoopGroup();
        final ServerBootstrap serverBootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .localAddress(new InetSocketAddress("localhost", 8080))
                .childHandler(new JerseyChannelInitializer(null, null, true, 20000000L, null, new ApplicationHandler(new Application()), 8192, null));
        final ChannelFuture bindFuture = serverBootstrap.bind();
        bindFuture.channel().closeFuture().addListener(c -> System.out.println("*** server closed"));
        bindFuture.sync();
        System.out.println("*** server started");
        webTarget = ClientBuilder.newClient().target("http://localhost:8080/");

    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        group.shutdownGracefully().addListener(f -> System.out.println("*** eventLoopGroup shutdown")).sync();
    }

    @Test
    void hork() {
        final Response response = assertDoesNotThrow(() ->
                webTarget.path("/hork")
                        .request()
                        .buildGet()
                        .invoke()
        );

        assertEquals(200, response.getStatus(), "Request had different status code then '200 OK'");
        assertEquals("blatz", response.readEntity(String.class));

    }

    @Test
    void hoopy () {
        final Response response = assertDoesNotThrow(() ->
                webTarget.path("/hork")
                        .request()
                        .buildPost(Entity.entity("Hello", MediaType.TEXT_PLAIN_TYPE))
                        .invoke()
        );

        assertEquals(204, response.getStatus(), "Request had different status code then '204 No Content'");
    }

}