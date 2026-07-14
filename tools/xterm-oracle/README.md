# KetraTerm xterm.js differential oracle

This directory contains a process-isolated, version-pinned terminal oracle built
on `@xterm/headless`. It is test tooling, not a production KetraTerm dependency
and not a source of normative terminal semantics.

The executable accepts one JSON request on standard input and emits one JSON
snapshot on standard output. Protocol version 1 supports exact byte chunks,
resizes, and explicit end-of-input markers. Passing `--server` selects a
JSON-lines protocol for high-volume campaigns: the process remains resident,
but every request still receives a fresh terminal so state cannot leak between
cases. Server errors are returned per request and do not poison later requests.

The snapshot exposes only public xterm.js state: retained grid cells, soft-wrap
links, cursor coordinates, selected modes, title changes, terminal response
bytes, colors, and public cell styles. State that xterm.js does not publicly
expose is omitted rather than fabricated.

## Verification

From the repository root:

```shell
./gradlew :ketraterm-testkit:xtermDifferentialTest
```

The Gradle task runs `npm ci`, executes the oracle's Node tests, then runs the
curated suite and 2,000 deterministic generated scenarios. `package-lock.json`
pins the exact dependency graph. Use `-PxtermDifferentialCases=N` to override
the generated count. The regular repository `test` task does not download npm
dependencies; xterm-specific tests are skipped unless a dedicated task enables
them.

Additional profiles are available for automation:

```shell
./gradlew :ketraterm-testkit:xtermDifferentialSmokeTest   # 100 cases
./gradlew :ketraterm-testkit:xtermDifferentialNightlyTest # 100,000 cases
./gradlew :ketraterm-testkit:xtermDifferentialReleaseAudit # 500,000 cases
```

Generated failures are deterministically shrunk by operation group and written
as replayable JSON under
`ketraterm-testkit/build/reports/xterm-differential/failures`.

## Correct interpretation

A mismatch means that two implementations disagree. It does not establish
which implementation is correct. Resolve every mismatch against the applicable
standard and KetraTerm's declared compatibility policy, then encode the outcome
as a focused semantic regression test.
