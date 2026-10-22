# Design Decisions

Each entry records the problem, the options, the choice, and what it costs.

### D-001: Speak the real Redis protocol (RESP2)
- **Context:** The server needs a wire format.
- **Options:** Invent a simple text protocol; use JSON over HTTP; implement RESP.
- **Decision:** RESP2, including inline commands.
- **Why:** `redis-cli`, `redis-benchmark` and every Redis client library then work unchanged, which gives free tooling and an objective correctness check against real Redis behaviour.
- **Trade-offs:** RESP needs a stateful incremental parser, more work than a line-based protocol.

### D-002: Incremental parser that consumes nothing until a command is complete
- **Context:** TCP delivers bytes in arbitrary chunks.
- **Options:** Read with a blocking stream and parse as you go; buffer bytes and retry parsing.
- **Decision:** Buffer and retry. `next()` returns `null` when incomplete and keeps the start position.
- **Why:** It works unchanged for the blocking and the non-blocking server, and it is easy to test (feed one byte at a time).
- **Trade-offs:** An incomplete large command is re-scanned on each new chunk. Pre-growing the buffer for known bulk lengths limits the copying.

### D-003: Single-threaded event loop as the default
- **Context:** Many clients, one shared dataset.
- **Options:** Thread per client with locks; a thread pool; one thread with NIO.
- **Decision:** NIO `Selector` event loop by default, with thread-per-client kept behind `--mode threads` for comparison.
- **Why:** There are no locks in the data layer, the code is easy to reason about, and it measured faster: 92k vs 74k GET/s, and 1.34M vs 0.69M pipelined.
- **Trade-offs:** Uses one CPU core for commands. A slow command blocks everyone.

### D-004: Store values as `String` decoded with ISO-8859-1
- **Context:** Redis values are arbitrary bytes.
- **Options:** `byte[]` with a wrapper class for map keys; `String` decoded as UTF-8; `String` decoded as ISO-8859-1.
- **Decision:** ISO-8859-1 strings.
- **Why:** Every byte 0–255 maps to exactly one char and back, so values are binary-safe. `String` also already has correct `equals`/`hashCode` for use as `HashMap` keys.
- **Trade-offs:** About twice the memory of `byte[]`, and `STRLEN` counts bytes, which is correct for Redis.

### D-005: Log absolute expiry times in the AOF
- **Context:** `EXPIRE k 10` means "10 s from now". Replaying it at restart would grant a fresh 10 s.
- **Decision:** Commands with relative TTLs are rewritten to `PEXPIREAT k <epoch-ms>` before logging. `SET … EX` becomes `SET` + `PEXPIREAT`.
- **Why:** Replay gives exactly the original deadline. Keys that expired while the server was down are deleted on load.

### D-006: Flush the AOF before sending replies
- **Context:** If a reply is sent first and the server then crashes, the client believes a write happened that was never logged.
- **Decision:** Replies are queued during a loop iteration and sent only after `Engine.afterBatch()` has written the AOF buffer.
- **Trade-offs:** Slightly higher latency per batch. The guarantee is "written to the OS"; surviving a power loss also needs `appendfsync always`.

### D-007: Circular buffer for lists instead of `ArrayList` / `LinkedList` / `ArrayDeque`
- **Why:** Lists need O(1) at both ends *and* O(1) by index. `ArrayList` is O(n) at the front, `LinkedList` is O(n) by index, and `ArrayDeque` has no index access.
- **Trade-offs:** `LREM`/`LTRIM` rebuild the buffer, which is O(n), the same as in Redis.

### D-008: Skip list (not `TreeMap`) for sorted sets
- **Why:** A balanced tree has no cheap "rank of X" or "element at rank N". A skip list with spans gives both in O(log n), with much simpler code than an order-statistic tree.
- **Trade-offs:** Probabilistic balance. Verified with a 5,000-operation property test against `TreeMap`.

### D-009: Web console as a separate HTTP bridge with a no-build frontend
- **Context:** Non-engineers need a visual tool, and browsers cannot speak raw TCP.
- **Options:** A WebSocket inside the server; a separate HTTP bridge; a desktop app.
- **Decision:** A separate `ConsoleServer` process using the JDK's built-in HTTP server, and plain HTML/JS.
- **Why:** The core server stays a pure RESP server, the console works against real Redis too, and there are no npm dependencies to install or update.
- **Trade-offs:** One extra hop, and the bridge holds one shared connection guarded by a lock (fine for a human-driven UI).

### D-010: Atomic rename for every file rewrite
- **Why:** Writing a temp file, fsyncing it, then renaming it atomically means a crash leaves either the old file or the new one intact, never a half-written file.
