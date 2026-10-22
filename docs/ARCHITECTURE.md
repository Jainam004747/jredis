# jredis Architecture

## Overview

jredis is organised in layers. Each layer only talks to the one below it, so each can be understood and tested on its own.

```
┌──────────────────────────────────────────────────────────────────┐
│ console/   ConsoleServer (HTTP+JSON) ── web GUI (index.html)      │  optional, separate process
│ client/    RespClient (used by the console, benchmark, tests)     │
├──────────────────────────────────────────────────────────────────┤
│ server/    EventLoopServer | ThreadedServer   (networking)        │
│            Engine          (runs commands, AOF, cron, INFO)       │
├──────────────────────────────────────────────────────────────────┤
│ command/   CommandTable + *Commands   (what each command does)    │
├──────────────────────────────────────────────────────────────────┤
│ store/     Database, ExpiryIndex, RList, SkipList, ZSet           │
│ persistence/ AppendOnlyFile, Snapshot                             │
│ protocol/  RespParser, Resp                                       │
└──────────────────────────────────────────────────────────────────┘
```

| Package | Responsibility | Key files |
|---|---|---|
| `protocol` | Bytes ↔ commands and replies | `RespParser` (incremental), `Resp` (sealed reply types + encoder) |
| `store` | Data in memory | `Database` (keyspace + expiry), `SkipList`, `RList`, `ZSet`, `ExpiryIndex` |
| `command` | Command semantics | `CommandTable` (lookup, arity, errors), one class per type family |
| `persistence` | Data on disk | `AppendOnlyFile` (log + replay + rewrite), `Snapshot` (binary dump) |
| `server` | Networking + lifecycle | `Engine`, `EventLoopServer`, `ThreadedServer`, `Config` |
| `client` | Talking to a server | `RespClient` (blocking, supports pipelining) |
| `console` | Web GUI | `ConsoleServer` (HTTP bridge), `Json`, `resources/console/index.html` |
| `bench` | Load testing | `Benchmark` (throughput + p50/p99 latency) |

## Request lifecycle (event-loop mode)

1. The `Selector` reports that client 7's socket is readable.
2. `EventLoopServer.read` reads up to 16 KB at a time and feeds the bytes to that connection's `RespParser`.
3. `RespParser.next()` returns a complete command such as `["SET", "k", "v"]`, or `null` if it still needs more bytes. Nothing is consumed until a whole command has arrived, so a command split across several TCP packets is handled naturally.
4. `Engine.execute` calls `CommandTable.execute`, which:
   - finds the `Spec` for `SET`;
   - checks the arity (number of arguments);
   - runs the handler;
   - turns `CommandException` and `WrongTypeException` into `-ERR …` replies, so a bad command never kills the connection.
5. The handler reads or changes the `Database`. If the command is a write and it succeeded, it is **propagated**: added to the AOF buffer, rewritten to an absolute form where needed (`EXPIRE` becomes `PEXPIREAT`).
6. The reply is encoded into the connection's output buffer. It is **not sent yet**.
7. After all ready sockets have been handled, `Engine.afterBatch()` writes the AOF buffer to the file. Only then are the queued replies written to the sockets. A client therefore never receives `OK` for a write that has not reached the operating system.
8. If a socket's send buffer is full (a slow client), the rest is kept and `OP_WRITE` interest is registered, so the loop never blocks on one client.
9. Every 100 ms, `Engine.cron()` runs active expiry, and once per second it fsyncs the AOF (`everysec` policy).

## Data structures

| Redis type | Java representation | Why |
|---|---|---|
| string | `String` (ISO-8859-1: one char per byte) | Binary-safe and simple |
| list | `RList`, a circular buffer | O(1) push and pop at both ends **and** O(1) access by index (LINDEX, LRANGE) |
| hash | `HashMap<String,String>` | O(1) field access |
| set | `HashSet<String>` | O(1) membership |
| zset | `ZSet` = `HashMap` member→score + `SkipList` | O(1) ZSCORE, O(log n) rank and range |

**Skip list with spans.** Each forward pointer stores how many nodes it skips. Summing the spans along the search path gives a node's rank in O(log n), which makes `ZRANK` and `ZRANGE key 1000 1010` fast. A property test compares it against `TreeMap` over 5,000 random operations.

**TTL index.** Active expiry needs a *random* key that has a TTL. `ExpiryIndex` keeps the keys in an `ArrayList` plus a key→position map. A random pick is then O(1), and removal is O(1) by moving the last element into the hole ("swap-remove").

## Expiry

- **Lazy:** every read calls `expireIfNeeded(key)` first, so an expired key is never returned.
- **Active:** `activeExpireCycle()` samples 20 keys that have a TTL and deletes the expired ones. If more than 25% of the sample had expired, it repeats, within a 1 ms budget. This bounds both the memory wasted by expired keys and the CPU spent cleaning them up.

## Persistence

| | Append-only file | Snapshot |
|---|---|---|
| Format | The RESP commands themselves | Binary: `JRDB` magic, version, typed entries, CRC32 checksum |
| Written | After every write batch (fsync per policy) | On `SAVE`/`BGSAVE` and at shutdown |
| Recovery | Replay the commands; a truncated last command is cut off | Load the entries; a checksum mismatch means the file is rejected |
| Trade-off | Loses at most about 1 s of writes (`everysec`); the file grows | Compact and fast to load, but loses everything since the last save |

Rewriting (`AppendOnlyFile.rewrite`) and saving (`Snapshot.save`) both write a temporary file, fsync it, then rename it atomically over the old one. A crash at any point leaves a complete old file or a complete new file, never a mix.

On startup, the AOF is used if it exists (it is the most complete record); otherwise the snapshot is used.

## Concurrency model

- **Event loop (default).** One thread owns everything, so the `Database` has no locks. Commands are short, so the loop stays responsive. The cost: one slow command (such as `KEYS *` on millions of keys) delays every client.
- **Threads.** Each client gets a thread blocked on its own socket. Since the `Database` is not thread-safe, one lock around `Engine` serialises all commands. Simpler code, but more memory per client and lock contention, and it measures slower (see [BENCHMARKS.md](BENCHMARKS.md)).

## Web console

```
browser ──HTTP/JSON──▶ ConsoleServer :8080 ──RESP/TCP──▶ jredis :6380
```

Browsers cannot open raw TCP sockets, so the console runs a small HTTP server that translates requests into RESP. For example, `GET /api/keys?pattern=user:*` becomes `KEYS user:*` followed by one pipelined `TYPE` and `PTTL` per key. The page is plain HTML and JavaScript with no build step. It binds to `127.0.0.1` because it has no login.
