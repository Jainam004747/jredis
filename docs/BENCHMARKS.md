# Benchmarks

Measured with the official `redis-benchmark` (from `redis-tools`) against jredis.

**Environment:** 2 vCPU Linux container, OpenJDK 21. The benchmark tool and the server share the same 2 CPUs, so absolute numbers are modest; the comparisons between designs are what matter. Persistence was on (`appendonly yes`, `appendfsync everysec`).

## Event loop vs thread per client (50 clients, 100,000 requests)

```bash
redis-benchmark -p 6380 -q -n 100000 -c 50 -t set,get,incr,lpush,zadd
```

| Command | Event loop (req/s) | Threads (req/s) | Event loop p50 |
|---|---:|---:|---:|
| SET | 66,890 | 54,025 | 0.52 ms |
| GET | 92,251 | 73,584 | 0.42 ms |
| INCR | 95,238 | 61,538 | 0.44 ms |
| LPUSH | 84,459 | 64,935 | 0.46 ms |
| ZADD | 83,682 | 63,654 | 0.46 ms |

The single-threaded event loop is 25–55% faster. The threaded server pays for 50 threads, context switches, and a global lock that serialises every command anyway.

## Pipelining (50 clients, 16 commands per round trip)

```bash
redis-benchmark -p 6380 -q -n 200000 -c 50 -P 16 -t set,get
```

| Command | Event loop | Threads (before fix) | Threads (after buffering replies) |
|---|---:|---:|---:|
| SET | 784,314 | 129,786 | 185,874 |
| GET | 1,342,282 | 369,686 | 689,655 |

Pipelining sends 16 commands per network round trip, which raises throughput about 10–15×.

**A measured optimisation:** the threaded server first wrote every reply straight to the socket, one system call per reply. Wrapping the output in a 16 KB `BufferedOutputStream`, flushed once per read, nearly doubled pipelined GET throughput. Without a benchmark, nobody would have guessed this was the bottleneck.

## SET is slower than GET

Every SET is also appended to the AOF buffer and written to the file once per batch. GET touches only memory. Running with `--appendonly no` closes most of the gap.

## Reproduce

```bash
./gradlew run --args="--port 6380"                       # terminal 1
redis-benchmark -p 6380 -q -n 100000 -c 50 -t set,get    # terminal 2
./gradlew run --args="bench --target localhost:6380"     # built-in tool with p50/p99
```
