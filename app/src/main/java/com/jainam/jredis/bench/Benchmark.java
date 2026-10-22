package com.jainam.jredis.bench;

import com.jainam.jredis.client.RespClient;
import com.jainam.jredis.protocol.Resp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * A small load generator in the spirit of redis-benchmark: N clients send requests as fast as
 * they can, and we report throughput (requests per second) and latency percentiles.
 *
 * <p><b>Why percentiles and not the average?</b> Averages hide the slow requests users notice.
 * p50 is the typical request; p99 is the slowest 1 in 100.
 *
 * <pre>
 *   bench --target localhost:6380 --clients 50 --requests 100000 --pipeline 1
 * </pre>
 */
public final class Benchmark {
    private Benchmark() { }

    record Result(String name, long requests, double seconds, double p50Micros, double p99Micros) {
        double rps() {
            return requests / seconds;
        }
    }

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 6380;
        int clients = 50;
        int requests = 100_000;
        int pipeline = 1;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--target" -> {
                    String[] hp = args[i + 1].split(":");
                    host = hp[0];
                    port = Integer.parseInt(hp[1]);
                }
                case "--clients" -> clients = Integer.parseInt(args[i + 1]);
                case "--requests" -> requests = Integer.parseInt(args[i + 1]);
                case "--pipeline" -> pipeline = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        System.out.printf(Locale.ROOT, "jredis benchmark: %s:%d, %d clients, %d requests, pipeline %d%n%n",
                host, port, clients, requests, pipeline);
        List<Result> results = new ArrayList<>();
        results.add(run("PING", host, port, clients, requests, pipeline, i -> List.of("PING")));
        results.add(run("SET", host, port, clients, requests, pipeline,
                i -> List.of("SET", "bench:key:" + (i % 10_000), "value-" + i)));
        results.add(run("GET", host, port, clients, requests, pipeline,
                i -> List.of("GET", "bench:key:" + (i % 10_000))));
        results.add(run("INCR", host, port, clients, requests, pipeline, i -> List.of("INCR", "bench:counter")));
        results.add(run("LPUSH", host, port, clients, requests, pipeline,
                i -> List.of("LPUSH", "bench:list", "item-" + i)));
        results.add(run("ZADD", host, port, clients, requests, pipeline,
                i -> List.of("ZADD", "bench:zset", Integer.toString(i % 1000), "member-" + i)));
        System.out.printf(Locale.ROOT, "%-8s %14s %12s %12s%n", "command", "requests/sec", "p50 (us)", "p99 (us)");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "%-8s %14.0f %12.0f %12.0f%n", r.name(), r.rps(), r.p50Micros(), r.p99Micros());
        }
        try (RespClient c = new RespClient(host, port)) {
            c.call("DEL", "bench:counter", "bench:list", "bench:zset");
        }
    }

    static Result run(String name, String host, int port, int clients, int requests, int pipeline,
                      IntFunction<List<String>> command) throws Exception {
        AtomicLong next = new AtomicLong();
        long[][] latencies = new long[clients][];
        CountDownLatch done = new CountDownLatch(clients);
        Exception[] failure = new Exception[1];
        long start = System.nanoTime();
        for (int c = 0; c < clients; c++) {
            final int id = c;
            Thread.ofPlatform().start(() -> {
                List<Long> mine = new ArrayList<>();
                try (RespClient client = new RespClient(host, port)) {
                    while (true) {
                        long first = next.getAndAdd(pipeline);
                        if (first >= requests) {
                            break;
                        }
                        int batch = (int) Math.min(pipeline, requests - first);
                        List<List<String>> cmds = new ArrayList<>(batch);
                        for (int k = 0; k < batch; k++) {
                            cmds.add(command.apply((int) (first + k)));
                        }
                        long t0 = System.nanoTime();
                        List<Resp> replies = client.pipeline(cmds);
                        long t = System.nanoTime() - t0;
                        if (replies.get(0) instanceof Resp.Error e) {
                            throw new IllegalStateException(name + " failed: " + e.message());
                        }
                        mine.add(t);
                    }
                } catch (Exception e) {
                    synchronized (failure) {
                        failure[0] = e;
                    }
                } finally {
                    latencies[id] = mine.stream().mapToLong(Long::longValue).toArray();
                    done.countDown();
                }
            });
        }
        done.await();
        if (failure[0] != null) {
            throw failure[0];
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long[] all = Arrays.stream(latencies).flatMapToLong(Arrays::stream).sorted().toArray();
        return new Result(name, requests, seconds, percentile(all, 50) / 1e3, percentile(all, 99) / 1e3);
    }

    static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
