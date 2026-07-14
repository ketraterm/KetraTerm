# KetraTerm xterm.js differential oracle

This directory contains a process-isolated, version-pinned terminal oracle built
on `@xterm/headless`. It is test tooling, not a production KetraTerm dependency
and not a source of normative terminal semantics.

The executable accepts one JSON request on standard input and emits one JSON
snapshot on standard output. Protocol version 1 supports exact byte chunks,
resizes, and explicit end-of-input markers. Every invocation creates a fresh
xterm.js terminal, preventing state from leaking between conformance cases.

The snapshot exposes only public xterm.js state: retained grid cells, soft-wrap
links, cursor coordinates, selected modes, title changes, and terminal response
bytes. State that xterm.js does not publicly expose is omitted rather than
fabricated.

## Verification

From the repository root:

```shell
./gradlew :ketraterm-testkit:xtermDifferentialTest
```

The Gradle task runs `npm ci`, executes the oracle's Node tests, then runs the
curated KetraTerm-versus-xterm.js JVM suite. `package-lock.json` pins the exact
dependency graph. The regular repository `test` task does not download npm
dependencies; its xterm-specific test class is skipped unless the dedicated
task enables it.

## Correct interpretation

A mismatch means that two implementations disagree. It does not establish
which implementation is correct. Resolve every mismatch against the applicable
standard and KetraTerm's declared compatibility policy, then encode the outcome
as a focused semantic regression test.
