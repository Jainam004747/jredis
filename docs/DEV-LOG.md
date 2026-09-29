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
