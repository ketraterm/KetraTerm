# Terminal PTY Agent Guide

`ketraterm-pty` owns local pseudo-terminal process lifecycle and raw stream
wiring. It exposes `TerminalConnector` implementations and convenience factories
returning the shared `TerminalSession`.

## Boundary

- Own process spawn, stdout delivery, stdin writes, native resize, and disposal.
- Keep transport bytes opaque. Parsing, grid mutation, and input encoding belong
  to their existing layers.
- Convenience assembly may configure the core before transferring it to a
  session, but runtime mutation remains session-owned.
- Bind host metadata and clipboard callbacks to the requesting session before
  starting output. PTY never implements platform UI or clipboard policy.
- Shell integration is explicitly selected and host-owned; do not install hooks
  or introduce a dependency on the optional OSC producer into production code.

## Lifecycle invariants

- Distinguish launching a process from starting its output delivery. A returned
  unstarted session still owns a live process and must be closed.
- Close owned resources on assembly/startup failure and preserve the original
  failure with cleanup failures suppressed.
- Reuse borrowed read storage only after the synchronous callback returns.
  Never retry a failed byte range.
- Report normal remote closure after both process exit and stdout drain.
  Local close cancels delivery and does not synthesize a remote exit code.
- Permit close from reader/watcher callbacks without joining a worker that is
  waiting for that callback. Teardown is idempotent.
- Keep foreground-process detection best-effort and separate from parsing or
  rendering.

## Testing

Use fake processes and controlled streams for lifecycle, ordering, failure, and
cleanup tests. Session-level behavior goes through `TerminalSession` and the
connector, not a PTY-specific runtime. Prefer explicit handshakes over sleeps.

Run `./gradlew :ketraterm-pty:test` for ordinary tests. Native process tests are
opt-in:

```text
./gradlew :ketraterm-pty:test --tests "io.github.ketraterm.pty.PtyRealProcessTest" "-Dterminal.pty.host=true"
```

See [native prerequisites](docs/pty4j-process-lifecycle.md#native-validation).
The [README](README.md) documents consumer ownership and
[Module.md](Module.md) identifies implementation components.
