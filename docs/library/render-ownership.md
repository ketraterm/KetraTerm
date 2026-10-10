# Render reader ownership

A session exposes borrowed render frames and a read-only shell metadata view.
The host retains mutable producers at construction; consumers do not recover
publication authority from the session.

| Access | Ownership |
| --- | --- |
| `TerminalSession.readPublishedFrame` | Borrows the latest copied cache; returns null before first publication and remains readable after closure. |
| `TerminalRenderPublisher.readCurrent` | Borrows a standalone publisher's latest cache. The publisher's owner controls writes. |
| `TerminalShellIntegrationView` | Thread-safe observation and primitive copies; separate reads may see different revisions. |
| `TerminalShellIntegrationState` | Producer-owned mutable state; closing a session does not close or clear a supplied host model. |

Frame callbacks must not modify or retain cache storage, close the session, or
reenter mutation/publication. A read does not request a fresh frame; publication
is controlled by render requests and generation observation.

Use scoped readers. The publisher's acquire/release bridges exist for cross-module
use and must be paired exactly once in `finally`. Inline readers preserve that
cleanup and Kotlin non-local returns without exposing buffer indexing. A cache
reference cannot detect every duplicate-release misuse by concurrent callers.

The [publication contract](../../ketraterm-render-cache/docs/triple-buffering-concurrency.md)
defines leasing and concurrency; the [session contract](../../ketraterm-session/docs/session-concurrency-locks.md)
defines synchronization. [Consumer fixtures](../../ketraterm-testkit/src/consumerTest/README.md)
verify the supported binary boundary. Lease measurements belong to
[the benchmark suite](../../ketraterm-benchmarks/README.md), not an API performance guarantee.
