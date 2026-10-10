# Terminal Completion Agent Guide

`ketraterm-completion` owns command-line completion contracts, tokenization,
structured source evaluation, ranking, and bounded in-memory learning. Read the
root [agent guide](../AGENTS.md) for cross-module ownership.

## Public API Surface

External consumers import only `io.github.ketraterm.completion.api` and
`io.github.ketraterm.completion.model`. Top-level declarations in `commandline`,
`engine`, `internal`, `matching`, `ranking`, `source`, `spec`, and
`stats` remain internal unless deliberately promoted into the public packages.
Use Kotlin visibility and module dependencies to enforce this boundary; do not
maintain a second declaration-name allowlist.

Update the [architecture guide](docs/completion-architecture.md) when changing a
public contract or package boundary. README examples are consumer entry points;
`Module.md` supplies package and maintainer context.

## Boundary

- Resolve one command-line context per merged-engine request and share it with
  every source and the ranker.
- Keep spec, matching, ranking, and learning evaluation free of host I/O.
  Host-provided suspending sources own bounded I/O and its dispatcher.
- Let the engine own source concurrency and cancellation. Sources do not create
  unowned scopes or launch their own jobs.
- Keep one bounded learning aggregate and immutable published snapshots.
  Plaintext replay and opaque ranking evidence have distinct eligibility rules.
- Leave process creation, terminal protocols, session lifecycle, UI scheduling,
  and persistent storage in their owning modules.

## Testing

Use deterministic unit tests for tokenization, replacement ranges, semantic
context, quoting, output bounds, ranking, and learning privacy. Assert exact
candidate edits and ordering rather than implementation details. Concurrent
source tests use coroutine test dispatchers or explicit handshakes and verify
progressive publication, failure isolation, and cancellation without sleeps.

For behavior changes, run formatting and the module tests from the repository
root:

```text
./gradlew spotlessApply :ketraterm-completion:test
```

Ranking-policy changes must pass `CompletionRankingReplayTest`. For performance
changes, use the completion benchmarks in `ketraterm-benchmarks` and compare the
relevant workload against a baseline.
