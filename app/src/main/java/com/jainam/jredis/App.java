package com.jainam.jredis;

import com.jainam.jredis.bench.Benchmark;
import com.jainam.jredis.console.ConsoleServer;
import com.jainam.jredis.server.Config;
import com.jainam.jredis.server.Engine;
import com.jainam.jredis.server.Server;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. One program, three tools:
 *
 * <pre>
 *   ./gradlew run --args="--port 6380"                  start the jredis server
 *   ./gradlew run --args="console --target localhost:6380 --http 8080"
 *                                                       start the web console for people
 *   ./gradlew run --args="bench --target localhost:6380" run the built-in benchmark
 * </pre>
 */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("console")) {
            ConsoleServer.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("bench")) {
            Benchmark.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("""
                    jredis: a Redis-compatible in-memory data store

                    Server:   --port 6380 --mode eventloop|threads --dir . --appendonly yes|no
                              --appendfsync everysec|always|no --save-on-shutdown yes|no
                    Console:  console --target localhost:6380 --http 8080
                    Bench:    bench --target localhost:6380 --clients 50 --requests 100000""");
            return;
        }
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("jredis: " + e.getMessage() + " (try --help)");
            System.exit(2);
            return;
        }
        Engine engine = new Engine(config);
        Server server = Server.create(engine);
        server.start();
        System.out.println("jredis is ready: connect with  redis-cli -p " + server.port()
                + "  or  nc localhost " + server.port());

        // Ctrl+C: stop cleanly so the AOF is flushed and a snapshot is written.
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("\njredis: shutting down, saving data...");
                server.close();
            } catch (Exception e) {
                System.err.println("jredis: error during shutdown: " + e.getMessage());
            } finally {
                stopped.countDown();
            }
        }));
        stopped.await();
    }
}
