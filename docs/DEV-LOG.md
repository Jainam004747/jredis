# Development Log

A record of every setup step and engineering decision: the command, and why it was run.

## 2026-09-28 - Environment and repository setup (Milestone M0)

### 1. Development environment: GitHub Codespaces
- **What:** a cloud Linux (x86_64) dev machine with VS Code in the browser.
- **Why:** nothing to install on Windows, and the same environment every time. It is also Linux on x86_64, which later projects need (the C compiler targets the x86-64 Linux calling convention).
- **Alternative considered:** WSL2 on Windows. It works offline and has no monthly hour limit, but needs local setup. Kept as a fallback.

### 2. Verify the toolchain
```bash
java -version; gradle --version; gcc --version; uname -m
```
- **Why:** confirm the JDK, build tool, C compiler and CPU architecture before writing code.
- **Result:** OpenJDK 25 (LTS), Gradle 9.7.1, gcc 13.3, x86_64.

### 3. Create the Gradle project
```bash
gradle init --type java-application --dsl kotlin --test-framework junit-jupiter \
  --project-name jredis --package com.jainam.jredis --java-version 25 \
  --no-split-project --no-incubating --use-defaults --overwrite
./gradlew test
```
- **Why Gradle:** reproducible builds, dependency management and one-command testing. The Gradle wrapper (`./gradlew`) pins the Gradle version, so anyone can build the repo without installing Gradle.
- **Why JUnit Jupiter:** the standard Java testing framework. This project is built test-first.
- **Why Java 25:** the current LTS release, and it was already installed in the Codespace.
- **Commit:** `chore: initialize Gradle project`

### 4. Repository configuration and documentation
| File | Why |
|---|---|
| `.gitignore` | keeps build output, IDE files and runtime data files out of git |
| `.gitattributes` | forces LF line endings, so Windows and Linux do not fight over line endings |
| `.github/workflows/ci.yml` | GitHub Actions runs the full test suite on every push and pull request |
| `README.md` | project summary, milestone checklist, build instructions |
| `docs/ARCHITECTURE.md` | how the system is built, updated every milestone |
| `docs/DECISIONS.md` | design decision records (context, options, decision, tradeoffs) |
| `docs/DEV-LOG.md` | this file |

- **Why these commits are separate:** each commit should hold one logical change. Scaffolding, configuration and documentation are different changes, so they are easier to review and to revert.
- **Commits:** `chore: add CI workflow, repo config and documentation templates`, `docs: add development log`

### Commit message convention
[Conventional Commits](https://www.conventionalcommits.org/): `feat:` new feature, `fix:` bug fix, `test:` tests, `refactor:` restructuring with no behavior change, `docs:` documentation, `chore:` build and tooling.

## 2026-10-21 - Building jredis (Milestones M1–M9 and the console)

### Local toolchain used while building
```bash
sudo apt-get install -y junit5 redis-tools   # JUnit jars for local test runs; real redis-cli / redis-benchmark
```
- **Why redis-tools:** testing against the *official* Redis client and benchmark is the strongest proof that the protocol implementation is correct.

### Build order (each step is one commit)
1. `protocol/`: RESP parser and encoder, test-first: one-byte-at-a-time feeding, pipelining, binary data, garbage input.
2. `store/`: keyspace with lazy + active expiry, circular-buffer list, skip list (property-tested against `TreeMap`).
3. `command/`: 90 commands with Redis-compatible errors, arity checks and AOF propagation rules.
4. `persistence/`: append-only file (replay, crash-tail repair, rewrite) and checksummed snapshots.
5. `server/`: Engine plus two front ends (NIO event loop and thread per client) behind `--mode`.
6. `console/` + `bench/`: web GUI with an HTTP-to-RESP bridge, and a load generator.

### Verification commands
```bash
./gradlew test                                       # 44 tests
redis-cli -p 6380 SET drink "double double"          # real client works
printf 'PING\r\n' | nc localhost 6380                # inline commands work
redis-benchmark -p 6380 -q -n 100000 -c 50 -t set,get
kill <pid>  # then restart: data is reloaded from appendonly.aof
```

### A bug found by a test
`ThreadedServer` tried to send "-ERR Protocol error" from the outer `catch` block, but try-with-resources had **already closed the socket** before the catch ran. The fix sends the error inside the read loop, before rethrowing. Lesson: resources in `try (...)` are closed *before* `catch` blocks execute.

### A performance fix found by a benchmark
Threaded mode did one `write()` system call per reply. Buffering the replies nearly doubled pipelined throughput (see BENCHMARKS.md).

### Codespace setup
`.devcontainer/devcontainer.json` installs `redis-tools` automatically and forwards ports 6380 (server) and 8080 (console).
