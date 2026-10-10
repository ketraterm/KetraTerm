# Terminal Completion Host Support Agent Guide

Read the [root guide](../AGENTS.md) first. This module owns host-neutral local
path resolution and suspending directory access for completion sources.

## Boundary

- Keep command parsing, quoting, specifications, source priorities, and ranking
  in `ketraterm-completion` or its host.
- Depend only on the completion API, coroutines, and JVM filesystem facilities;
  keep Swing, IntelliJ, workspace, session, and product types outside this module.
- Resolve only proven-local working-directory URI authorities. Never reinterpret
  an unsupported remote URI as a local path.
- Scanner implementations own dispatcher changes for blocking access. Request
  jobs, replacement, and cancellation remain host-owned; source parallelism
  belongs to the merged engine.

## Scan invariants

- Bound raw directory visits independently of matching and candidate limits.
  Treat the monotonic time budget as best-effort, not a hard I/O deadline.
- Preserve deterministic ordering of retained entries and case-insensitive
  prefix matching. Missing and non-directory paths are normal empty results;
  propagate operational failures and cancellation.
- Scan each request. Do not cache directory contents using file keys or
  last-modified timestamps as a content version: those values cannot prove
  that the entries are unchanged.
- Close directory streams on every exit path and skip children that disappear
  during enumeration without hiding unrelated filesystem failures.

## Validation

For behavior changes, test URI authority and lexical path handling, visit/time
bounds, ordering and prefix matching, cancellation, disappearing entries, and
operational failures as applicable. Use temporary directories and injected
clocks or dispatchers rather than wall-clock timing assertions.

Run formatting and `./gradlew :ketraterm-completion-host:test` from the repository
root. Update [README.md](README.md) when consumer usage changes and
[Module.md](Module.md) when dependencies or component responsibilities change.
