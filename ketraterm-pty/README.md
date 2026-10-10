# KetraTerm PTY (`:ketraterm-pty`)

Local pseudo-terminal process lifecycle, raw byte transport, and convenience
session construction, backed by JetBrains [Pty4J](https://github.com/JetBrains/pty4j).
The returned runtime is the transport-neutral `TerminalSession`.

Add `io.github.ketraterm:ketraterm-pty` alongside the library entry point you use;
see the [repository setup](../README.md). For a custom remote transport, use the
[session module](../ketraterm-session/README.md) directly.

## How to Use

`TerminalSessions.createLocalPty(options)` launches the child process immediately
and returns a session in `Created` state. Output delivery starts only when you
call `session.start(columns, rows)`. This lets a host register the session and
bind its view before startup output can trigger callbacks.

Use `TerminalSessions.localPty(options)` when output need not wait for view
registration. Both factories clean up if their own assembly or startup fails.

The host owns the returned session and must close it, including when it abandons
startup. If registration installs host resources, the host must also release
those resources on failure. Process creation, resizing, and disposal can block;
schedule them outside the UI thread. Register Swing views on the EDT.

Send input through the session, resize through its viewport APIs, and observe
`session.state` for completed closure. Local close discards pending outbound
work and does not manufacture a process exit code. See the
[session lifecycle and input contract](../ketraterm-session/README.md#input-and-lifecycle)
and the [Swing view example](../ketraterm-ui-swing/README.md#how-to-use).

<a id="how-to-extend-custom-key-mappings-or-shells"></a>

## Launch options

`PtyOptions.create` builds an immutable configuration; `copy` and `toBuilder`
support changes.

Pass the executable and each argument as separate command elements. Shell
operators such as pipes are interpreted only if the chosen executable is a
shell and its arguments request command evaluation.

```kotlin
import io.github.ketraterm.pty.PtyOptions
import java.nio.file.Path

fun localOptions(command: List<String>, directory: Path): PtyOptions =
    PtyOptions.create { draft ->
        draft.command = command
        draft.workingDirectory = directory
        draft.environment = PtyOptions.defaultEnvironment() + ("GIT_EDITOR" to "vim")
        draft.columns = 100
        draft.rows = 30
        draft.maxHistory = 2_000
    }
```

Defaults select `COMSPEC` or `cmd.exe` on Windows; elsewhere, `SHELL` or
`/bin/sh` with `-l`. The default working directory is the user's home.
`defaultEnvironment()` copies the current process environment and sets the
shared `TERM` and `COLORTERM` identity. Replacing `environment` supplies the
complete child environment; missing variables are not added afterward.

Configure `inputPolicy` for encoding choices; product key bindings belong in
the consuming UI.

## Host events

`PtyEventListener` receives titles, bells, palette changes, and host requests.
Delegate to `PtyEventListener.NONE` to override only the callbacks you need.
Post UI updates to the EDT and return promptly.

Metadata callbacks run during session mutation, usually on the PTY reader thread; host theme changes can invoke
them on the calling thread. Do not reenter session mutation or wait for the UI
thread. There is no initial event replay. Callback exceptions reach
`listenerFailed(session, exception: Exception)`; exceptions from that callback
are ignored.

`hostPolicy` controls terminal-triggered host actions. The listener implements
the permitted action; selecting a callback does not override policy. Advertise
only implemented host actions through `modeReportCapabilities`. See
[host policy](../ketraterm-host/README.md#host-policy).

## Clipboard reads

Implement the suspending `PtyEventListener.readClipboard(session, request)` to
supply permitted OSC 52 reads. The default reports unavailable data. The bridge
attaches the requesting session before output starts.

Reads run on the session I/O dispatcher without parser/input locks. Follow the
`TerminalClipboardReader` consent, selection, and cancellation contract; await
host readiness and earlier posted writes before native access. Provider failures
use the content-free session read audit rather than `listenerFailed`. See
[clipboard lifetime](../ketraterm-session/docs/session-concurrency-locks.md#clipboard-read-lifetime-and-output-commitment).

## Shell integration selection

`PtyOptions.shellIntegration` defaults to `null`. PTY creation does not install
shell scripts. Select the optional `OscShellIntegration` producer for compatible
shell hooks, or supply a host model through `TerminalShellIntegrationFactory.host`.
A `startupCommand` requires a selected integration and waits for its prompt
readiness. The caller installs the required shell hooks before launch and owns
any host-supplied producer.

See the [session composition guide](../ketraterm-session/README.md#host-owned-shell-integration)
and [optional OSC integration](../ketraterm-shell-integration/README.md).

## Direct connectors

`PtyConnectors.create(command, env, workingDirectory, columns, rows)` launches a
process and returns an unstarted `TerminalConnector`. Its environment default is
`System.getenv()`; pass `PtyOptions.defaultEnvironment()` when you want KetraTerm's
terminal identity. Start or transfer the connector to a session, and close it
if assembly is abandoned.

`PtyConnector` can also wrap an existing Pty4J process. The connector then owns
its process and streams. Prefer the transport interface for composition;
PTY-specific `waitFor()` waits for process exit, not completion of output
delivery. Foreground-process names are best-effort metadata; Windows uses a
descendant heuristic and does not inspect applications inside SSH or WSL.

## Sub-Documentation

- [Process lifecycle](docs/pty4j-process-lifecycle.md): output ordering, worker
  threads, failure, shutdown, resizing, and native test requirements.
- [Module ownership](Module.md): implementation components and dependencies.
