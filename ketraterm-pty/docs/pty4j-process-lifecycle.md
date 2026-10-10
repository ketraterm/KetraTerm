# Pty4j Local Process Lifecycle & Watcher Threads

The local PTY connector adapts a Pty4J process to the ordered raw-byte
`TerminalConnector` contract. Session construction and consumer examples are in
the [README](../README.md). This guide describes connector lifecycle and the
native integration seam.

## 1. Threading Architecture

Creating the connector launches its child process. Calling `start(listener)`
starts two daemon threads; it permits one start and rejects a closed connector.

| Worker | Default name | Work |
|---|---|---|
| Reader | `terminal-pty-reader` | Read stdout and synchronously invoke `onBytes` in stream order. |
| Watcher | `terminal-pty-watcher` | Wait for process exit, then wait for stdout delivery to finish before reporting normal closure. |

The reader allocates one byte buffer at startup, by default 8,192 bytes, and
reuses it. The listener borrows only the supplied range for the duration of
`onBytes`; copy data that must outlive the callback. Chunks need not align with
UTF-8 scalars, escape sequences, or terminal frames. The session processes them
synchronously and owns parsing.

The watcher can observe process exit before the final read. It waits for EOF and
completed byte callbacks before `onClosed(exitCode)`. Reader EOF alone does not
report closure while the process is still running. `PtyConnector.waitFor()`
waits only for process exit; it is not an output-drain boundary.

Daemon workers do not keep the JVM alive. Hosts must still close their sessions
to release native resources.

## Failure and local close

A reader or byte-listener exception is retained in `PtyConnector.failure` and
reported through `onError`. The connector closes the process and both streams,
then invokes `onClosed(null)`. It does not retry the failed chunk or replace the
original failure with a later process exit. Connector callbacks may close
reentrantly.

Local close cancels pending delivery rather than draining it. It does not emit a
remote `onClosed` callback; the session records its own local termination.
Exceptions arriving after local close are teardown and do not become remote
failures.

Cleanup requests process destruction and closes both streams. A call outside
the connector workers makes bounded joins of the reader and watcher. A worker
does not join itself or its peer, which may be waiting for the current callback.
Close is idempotent, but native destruction and stream cleanup can block. Keep
it off a UI thread.

## Writes and resize

Direct connector writes validate the byte range, serialize access to stdin, and
flush the supplied range before returning. Closed connectors ignore valid
writes. The session's outbound writer orders user input and terminal replies;
do not write through a retained connector after transferring it to a session.

Resize requires positive dimensions and synchronously delegates to
`WinSize(columns, rows)` while the connector is open. Use session resize APIs
for a session-owned connector so grid reflow, transport dimensions, and viewport
metadata are coordinated.

## 2. Windows ConPTY Considerations

The factory enables Pty4J's ConPTY option. Platform process creation and native
library loading are owned by Pty4J; startup may fail when the local native
environment or requested executable is unavailable. Initial dimensions are
supplied during process creation and propagated again before session delivery
starts.

Foreground-process lookup uses the Unix terminal's foreground process group
when available. Windows selects the newest live descendant, which can be a
background child. Missing permissions, unavailable metadata, closure, or an
oversized descendant tree can yield no name. Applications inside SSH or WSL are
not inspected. Treat this information as presentation metadata rather than a
process-control guarantee.

## Native validation

Ordinary module tests use fake process streams and do not require a native shell.
`PtyRealProcessTest` is skipped unless `terminal.pty.host=true`:

```text
./gradlew :ketraterm-pty:test --tests "io.github.ketraterm.pty.PtyRealProcessTest" "-Dterminal.pty.host=true"
```

The native suite requires working Pty4J support and a local shell: `/bin/sh` on
Unix, or `cmd.exe` and `powershell.exe` on Windows. Its OSC 52 byte harness also
requires `node` on PATH and the repository's `tools/osc52/osc52.mjs`.
Tests use explicit output/readiness signals and deadlines; skipped native tests
do not establish platform support for the current environment.
