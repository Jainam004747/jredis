package com.jainam.jredis.server;

import com.jainam.jredis.client.RespClient;
import com.jainam.jredis.protocol.Resp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * End-to-end tests over real TCP sockets. Every test runs against BOTH server designs, which
 * proves they behave identically from a client's point of view.
 */
class ServerIntegrationTest {

    @TempDir
    Path dir;

    private Server startServer(Config.Mode mode) throws Exception {
        Config config = new Config(0, mode, dir, true, com.jainam.jredis.persistence.AppendOnlyFile.Fsync.ALWAYS,
                "dump.jrdb", "appendonly.aof", true);
        Server server = Server.create(new Engine(config));
        server.start();
        return server;
    }

    @ParameterizedTest
    @EnumSource(Config.Mode.class)
    void basicCommandsOverTcp(Config.Mode mode) throws Exception {
        try (Server server = startServer(mode); RespClient c = new RespClient("localhost", server.port())) {
            assertEquals(Resp.PONG, c.call("PING"));
            assertEquals(Resp.OK, c.call("SET", "drink", "double double"));
            assertEquals(Resp.bulk("double double"), c.call("GET", "drink"));
            assertInstanceOf(Resp.Error.class, c.call("LPUSH", "drink", "x"));
            assertEquals(Resp.PONG, c.call("PING")); // an error does not break the connection
        }
    }

    @ParameterizedTest
    @EnumSource(Config.Mode.class)
    void pipelinedCommandsAreAnsweredInOrder(Config.Mode mode) throws Exception {
        try (Server server = startServer(mode); RespClient c = new RespClient("localhost", server.port())) {
            List<List<String>> cmds = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                cmds.add(List.of("INCR", "counter"));
            }
            List<Resp> replies = c.pipeline(cmds);
            for (int i = 0; i < 1000; i++) {
                assertEquals(Resp.integer(i + 1), replies.get(i));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Config.Mode.class)
    void manyClientsIncrementingConcurrentlyLoseNoUpdates(Config.Mode mode) throws Exception {
        int clients = 20;
        int perClient = 500;
        try (Server server = startServer(mode)) {
            CountDownLatch done = new CountDownLatch(clients);
            AtomicInteger errors = new AtomicInteger();
            for (int t = 0; t < clients; t++) {
                Thread.ofPlatform().start(() -> {
                    try (RespClient c = new RespClient("localhost", server.port())) {
                        for (int i = 0; i < perClient; i++) {
                            c.call("INCR", "shared");
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            assertEquals(0, errors.get());
            try (RespClient c = new RespClient("localhost", server.port())) {
                assertEquals(Resp.bulk(Integer.toString(clients * perClient)), c.call("GET", "shared"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Config.Mode.class)
    void inlineCommandsWorkFromNetcat(Config.Mode mode) throws Exception {
        try (Server server = startServer(mode); Socket s = new Socket("localhost", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write("PING\r\nECHO \"hi there\"\r\nQUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String all = new String(in.readAllBytes(), StandardCharsets.US_ASCII); // QUIT closes the socket
            assertEquals("+PONG\r\n$8\r\nhi there\r\n+OK\r\n", all);
        }
    }

    @ParameterizedTest
    @EnumSource(Config.Mode.class)
    void protocolErrorClosesOnlyThatConnection(Config.Mode mode) throws Exception {
        try (Server server = startServer(mode)) {
            try (Socket s = new Socket("localhost", server.port())) {
                s.getOutputStream().write("*1\r\n+bad\r\n".getBytes(StandardCharsets.US_ASCII));
                String reply = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertEquals(true, reply.startsWith("-ERR Protocol error"));
            }
            try (RespClient c = new RespClient("localhost", server.port())) {
                assertEquals(Resp.PONG, c.call("PING"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Config.Mode.class)
    void dataSurvivesRestart(Config.Mode mode) throws Exception {
        try (Server server = startServer(mode); RespClient c = new RespClient("localhost", server.port())) {
            c.call("SET", "drink", "tea");
            c.call("RPUSH", "cart", "coffee", "donut");
            c.call("ZADD", "lb", "10", "a", "20", "b");
            c.call("SET", "temp", "x", "EX", "1000");
        }
        try (Server server = startServer(mode); RespClient c = new RespClient("localhost", server.port())) {
            assertEquals(Resp.bulk("tea"), c.call("GET", "drink"));
            assertEquals(Resp.bulkArray(List.of("coffee", "donut")), c.call("LRANGE", "cart", "0", "-1"));
            assertEquals(Resp.bulk("20"), c.call("ZSCORE", "lb", "b"));
            long ttl = ((Resp.Int) c.call("TTL", "temp")).value();
            assertEquals(true, ttl > 990 && ttl <= 1000, "TTL kept its absolute deadline, got " + ttl);
        }
    }
}
