# Module ketraterm-session

# KetraTerm Session (`:ketraterm-session`)

`ketraterm-session` is the runtime synchronization boundary between transport, parser, core, input encoding, and render publication.

It uses coroutines for lifecycle orchestration, synchronized-output timeout handling, and conflated render publication. Transport byte consumption, parser/core mutation, ordinary input encoding and borrowed frame reads remain synchronous. Ordinary input and core-response batches use a bounded byte queue. Paste and text replacement retain bounded source data with admission-time modes and policy; one I/O coroutine streams their encoding and performs all ordered connector writes outside parser/input locks.

OSC 52 reads use an optional session-bound suspending provider. Session owns the deadline, permission revalidation, one active request, and a bounded owned reply; product hosts supply consent and native access. Missing providers return an empty reply when terminal responses are permitted.

## Runtime model

- `TerminalSession.state` retains `Created`, `Running`, or `Closed(TerminalSessionCloseEvent)`.
- `TerminalSession.renderGeneration` publishes only successfully promoted frames.
- `TerminalSession.renderPublisher` owns the leased primitive cache consumed by renderers.
- A session publishes one active render viewport; a new viewport request replaces the previous one. Independently scrolling views of the same session are unsupported. Separate sessions are separate terminal pipelines, not additional views of one process.
- `mutationLock` protects parser/core mutation and borrowed frame reads.
- Reentrant `outboundWriteLock` protects encoding and atomic queue admission. Native writes occur outside it.
- The transport contract already guarantees serial, ordered inbound byte delivery, so no additional inbound lock is used.

See [session-concurrency-locks.md](docs/session-concurrency-locks.md) and [asynchronous-render-coalescing.md](docs/asynchronous-render-coalescing.md).

## Usage

```kotlin
val terminal = TerminalBuffers.create(width = 80, height = 24)
val session = TerminalSession.create(
    terminal = terminal,
    connector = connector,
    hostEvents = hostEventSink,
    hostPolicy = HostPolicy(),
    inputPolicy = TerminalInputPolicy(),
)

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
scope.launch {
    session.renderGeneration.collect {
        session.renderPublisher.readCurrent { published ->
            rendererCache.updateFrom(published)
        }
    }
}
scope.launch {
    session.state.collect { state ->
        if (state is TerminalSessionState.Closed) {
            // React to local close, remote exit, or transport failure.
        }
    }
}

session.start(columns = 80, rows = 24)
session.requestRender(scrollbackOffset = 0)
```

Collectors own their scopes. Closing the session emits `Closed` before its child jobs are cancelled, so current and late collectors can observe the terminal lifecycle result.

## Host-owned shell editing

Provide `shellCommandLineSource` when the host already owns the shell editor model:

```kotlin
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

val shellCommandLine = MutableStateFlow<TerminalShellCommandLineSnapshot?>(null)
val session = TerminalSession.create(
    terminal = terminal,
    connector = connector,
    shellCommandLineSource = shellCommandLine.asStateFlow(),
)

// Publish after processing the corresponding terminal output and geometry.
shellCommandLine.value = TerminalShellCommandLineSnapshot(
    commandText = "git status",
    cursorOffset = 10, // UTF-16 offset in commandText.
    cursorColumn = 14, // Zero-based live-grid anchor after a four-cell prompt.
    cursorRow = 0,
)
shellCommandLine.value = null // The command starts, or editing context is unavailable.
```

The supplied source is authoritative for the session's lifetime, including `null`; it never falls back to OSC prompt extraction. Omit it to retain the default OSC 133 behavior. `PtyOptions.shellCommandLineSource` forwards the same contract through local PTY creation. The host owns source lifetime, text bounds, and publication ordering, including new anchors after resize. `activeShellCommandLine()` reads its current value; `activeShellCommandLineRevision` observes changes only while subscribed. Closing the session stops its observation without closing the host source.

This source replaces active editing context only. It does not replace command-history recording or current-directory metadata. Hosts can keep directory context in their completion provider, or update `shellIntegrationState.recordCurrentWorkingDirectory` with a validated file URI and deny OSC 7 through `HostPolicy.currentWorkingDirectoryPolicy`. Hosts also own prompt readiness and startup submission; combining this source with `startupCommand` is rejected. See the [concurrency contract](docs/session-concurrency-locks.md) for publication and ownership details.
