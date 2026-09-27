---
name: test
description: Run this project's tests and report results. Detects the project's own test runner, picks the narrowest useful scope (single test / module / all), and analyzes failures in detail.
allowed-tools: Bash, Glob, Grep
---

## Step 1 — Find the Test Runner

Read it off the project instead of assuming a stack. The build file that exists tells you the runner:

| Marker file                     | Runner     | All              | One module / package       | One test                                             |
| ------------------------------- | ---------- | ---------------- | -------------------------- | ---------------------------------------------------- |
| `gradlew`, `build.gradle(.kts)` | Gradle     | `./gradlew test` | `./gradlew :<module>:test` | `./gradlew test --tests "fully.qualified.ClassName"` |
| `pom.xml`                       | Maven      | `./mvnw test`    | `./mvnw -pl <module> test` | `./mvnw test -Dtest=ClassName`                       |
| `package.json`                  | npm script | `npm test`       | `npm test -w <workspace>`  | `npm test -- <path-or-pattern>`                      |
| `pyproject.toml`, `pytest.ini`  | pytest     | `pytest`         | `pytest <dir>`             | `pytest <file>::<test>`                              |
| `go.mod`                        | go         | `go test ./...`  | `go test ./<pkg>/...`      | `go test -run <TestName> ./<pkg>`                    |
| `Cargo.toml`                    | cargo      | `cargo test`     | `cargo test -p <crate>`    | `cargo test <name>`                                  |

```bash
ls gradlew build.gradle.kts build.gradle pom.xml package.json pyproject.toml go.mod Cargo.toml 2>/dev/null
```

For `package.json`, read the actual scripts — the test command is whatever the project defined, and the
underlying runner (vitest, jest, playwright) changes how you pass a filter:

```bash
node -e "console.log(require('./package.json').scripts)"
```

If several markers exist (a monorepo with a server and a web app), run the one that covers the changed
files, not everything.

## Step 2 — Pick the Narrowest Scope

Match the scope to what changed — a full run on every edit wastes minutes and buries the failure you
care about.

```bash
git diff --name-only HEAD          # uncommitted work
git diff --name-only origin/HEAD...HEAD   # this branch
```

One source file changed → run its test file. One module → that module. Broad or unclear → everything.

## Step 3 — Run and Get Detail on Failures

Run the chosen command. When a failure needs more output, add the runner's verbose flag rather than
re-running blind: Gradle `--info`, Maven `-X`, pytest `-vv`, vitest/jest `--reporter=verbose`,
go `-v`, cargo `-- --nocapture`.

## Step 4 — Report

- Total / passed / failed / skipped, and execution time
- For each failure: test name, failure message, root cause, the relevant stack or trace lines
- State which command was run, so the scope of the result is visible

If tests failed, read the relevant source files and name the most likely fix. A test that fails because
the code is wrong and a test that fails because the test is stale need opposite fixes — say which one
this is.
