# Command-finished alert verification

Baseline: `1bfaaf9eb`, 2026-10-29. The original scoped root suites passed before
production edits. New focused regressions first failed compilation because the
elapsed-duration query, alert settings, and eligibility policy were absent.

| Suite | Baseline tests | Verified tests | Failures | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Session, including default dispatcher | 497 | 509 | 0 | 0 |
| Shell integration | 73 | 73 | 0 | 0 |
| Workspace, including default dispatcher | 126 | 130 | 0 | 6 |
| Standalone | 212 | 235 | 0 | 0 |
| IntelliJ plugin | See below | 267 | 0 | 0 |
| Retained compiled clients | — | 46 | 0 | 0 |

The six native PTY cases retain their existing opt-in guard. This change does not
alter shell hooks or PTY behavior. The first plugin baseline stopped from native
JVM memory exhaustion before assertions. Final plugin verification used full
JetBrains JDK 25 for Gradle and instrumentation. A fresh compilation removed ten
stale constructor-linkage failures in the unchanged profile/restore tests;
all 267 tests then passed without disabling tests or changing their assertions.

Focused evidence:

- [Duration regressions](../../ketraterm-session/src/test/kotlin/io/github/ketraterm/session/TerminalShellIntegrationDurationTest.kt): clock rollback, nanosecond wrap, bounds, abandonment, eviction, and synchronous capture.
- [Session regressions](../../ketraterm-session/src/test/kotlin/io/github/ketraterm/session/TerminalSessionTest.kt): initial/live notification permission, closure, and unavailable policy ownership.
- [Shared policy regressions](../../ketraterm-workspace/src/test/kotlin/io/github/ketraterm/workspace/TerminalCommandFinishedAlertsTest.kt): defaults, inclusive thresholds, overflow, unknown duration, lifecycle, focus, and denial.
- [Standalone callbacks](../../ketraterm-app/src/test/kotlin/io/github/ketraterm/app/ui/TabManagerStartupTest.kt) and [IDE callbacks](../../ketraterm-intellij-plugin/src/test/kotlin/io/github/ketraterm/intellij/services/KetraTermProjectTerminalPersistenceTest.kt): real workspace routing, focus changes, live preferences, revoked session permission, coalescing, slot release, and disposal.

Configuration tests cover legacy defaults, persistence, malformed/overflowing
standalone values, normalized IDE bounds, and settings controls. Existing shell
marker ordering, malformed/chunked input, duplicate completions, and cleanup
regressions remain in the owning suites.

The public session API check passes with additive methods only; metadata
constructor/copy signatures and the public shell-state constructors are unchanged.
Rendering paths are unchanged; no new per-frame allocation claim is made.

Final checks also passed `publishedConsumerTest`: independent Java/Kotlin source
consumers with Gradle metadata and POM-only resolution, on Kotlin 2.4.20 and 2.4.0,
plus all 46 retained-client upgrade cases. Changed Markdown links were checked
locally (94 targets).

`graphify update .` completed with sequential extraction: 19,045 nodes and
46,835 edges. Its Kotlin extractor reported ten partially parsed files; Kotlin
compilation and the passing suites remain authoritative for source correctness.
