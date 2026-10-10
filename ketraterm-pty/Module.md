# Module ketraterm-pty

Local PTY process and connector lifecycle, with convenience construction of the
shared session runtime. Public APIs live in `io.github.ketraterm.pty`; the
[README](README.md) covers host assembly.

## Architectural Role & Data Flow

| Component | Responsibility |
|---|---|
| `PtyOptions` | Validated launch/session snapshot with detached command and environment collections. |
| `PtyConnectors` / `Pty4jProcessFactory` | Launch a local process and expose raw transport. |
| `PtyConnector` | Ordered stdout delivery, synchronized stdin writes, process exit, resize, and disposal. |
| `PtyForegroundProcessDetector` | Best-effort native foreground-group lookup or Windows descendant heuristic. |
| `TerminalSessions` / `PtySessions` | Assemble a session, attach host callbacks, and optionally start delivery. |
| `SessionHostEventBridge` | Bind metadata and clipboard operations to the owning session. |

The connector owns daemon reader/watcher threads. Session owns parser/core
serialization and the outbound writer. Host callbacks are forwarded from session
processing; PTY transport itself does not interpret output or encode input.

Creating a local session launches the process but leaves delivery unstarted.
Assembly failure closes the connector; immediate-start failure closes the
session. A successfully returned session belongs to its caller. Supplied shell
producers remain host-owned.

## Upstream Dependencies

The Gradle API surface exposes session and Pty4J. Core, host, input, protocol,
transport, and JNA are implementation dependencies. Optional OSC shell integration
is selected by consumers; this module uses it only in tests. Swing and product
policy remain outside PTY.

<a id="maintenance"></a>

## Lifecycle testing

The internal process seam isolates native process access. Lifecycle tests use
controlled streams to exercise output draining, failure notification, and
reentrant close from connector callbacks.

[Process lifecycle](docs/pty4j-process-lifecycle.md) defines worker, teardown, and
native-test contracts. The repository feature and gap maps remain authoritative
for capability scope.
