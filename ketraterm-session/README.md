# KetraTerm Session (`:ketraterm-session`)

`ketraterm-session` is the runtime synchronization boundary between transport, parser, core, input encoding, and render publication.

It uses coroutines for lifecycle orchestration, synchronized-output timeout handling, and conflated render publication. Transport byte consumption, parser/core mutation, ordinary input encoding and borrowed frame reads remain synchronous. Ordinary input and responses use a bounded byte queue. Paste and text replacement retain bounded source data with admission-time modes and policy; one I/O coroutine streams their encoding and performs all ordered connector writes.

OSC 52 reads use an optional session-bound suspending provider. Session owns the deadline, permission revalidation, one active request, and a bounded owned reply; product hosts supply consent and native access. Missing providers return an empty reply when terminal responses are permitted.

`TerminalBuffers.create` returns `TerminalRenderBuffer`, combining core and render capabilities. Hosts with separate collaborators use `TerminalSession.create(terminal, renderReader, connector)`; both must describe the same state. Initial render dimensions are checked before connector ownership transfers.

Customization belongs in normal assembly: `inputEncoderFactory` creates independent admission and bulk encoders using session-supplied modes, ordered output, and policy. Both instances must honor policy updates. `parserFactory` receives the assembled command sink and its live clipboard-write budget, preserving clipboard reads, startup, shell events, and resize coordination. Factories create fresh instances without I/O or jobs; failed construction leaves the connector with its caller. The low-level constructor retains caller-owned parser mapping.

## Runtime model

- `TerminalSession.state` retains `Created`, `Running`, or `Closed(TerminalSessionCloseEvent)`.
- `TerminalSession.renderGeneration` publishes only successfully promoted frames.
- `TerminalSession.readPublishedFrame` borrows the latest copied cache; the publisher stays private.
- A session publishes one active render viewport; a new viewport request replaces the previous one. Independently scrolling views of the same session are unsupported. Separate sessions are separate terminal pipelines, not additional views of one process.
- `mutationLock` protects parser/core mutation and borrowed frame reads.
- Reentrant `outboundWriteLock` protects encoding and atomic admission; native writes never hold it.
- Input return means acceptance, not write completion. Queue exhaustion or write failure closes the session with a failure; close discards pending bytes.
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
        session.readPublishedFrame { published ->
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

Collectors own their scopes. The session retains `Closed` after cleanup and final
frame publication. Current and late collectors can observe that result from
caller-owned scopes independently of the cancelled session workers.

After closure starts, input, render requests and policy/presentation setters are
ignored. Resize rejects with `IllegalStateException` (dimension validation still
runs first). Already-admitted work and parser EOF finish before `Closed` is
published; retained frames and mode/palette reads then remain available without
further mutation or publication. A new live terminal requires a new session.

The session keeps its mutable core private. Read through its synchronized frame
and mode APIs; a retained constructor input belongs exclusively to the session
until `state` reaches `Closed`. Custom parser assembly retains its host-adapter
wiring, while the session owns its admission lock. Rendering consumers must hold
a `readPublishedFrame` lease for every access to a published cache and its arrays.
The callback must not mutate or retain that storage, close the session, or reenter
session mutation. Empty publication returns null; exceptions and Kotlin non-local
returns release the lease. Closed sessions retain their final published frame.

## Host-owned shell integration

A plain session has no shell producer. `TerminalSession.create` wires the selected
producer to parsed host events. With the low-level constructor, the caller owns
its custom parser and host-event wiring; an already-built parser is not wrapped.

An IDE that already owns shell integration can pass its semantic metadata and
versioned editing projection and readiness flow directly:

```kotlin
import io.github.ketraterm.session.TerminalShellCommandLineState
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

val shellState = TerminalShellIntegrationState()
val commandLine = TerminalShellCommandLineState()
val promptReady = MutableStateFlow(false)
val session = TerminalSession.create(
    terminal = terminal,
    connector = connector,
    shellIntegration = TerminalShellIntegrationFactory.host(
        state = shellState,
        commandLine = commandLine,
        promptReady = promptReady.asStateFlow(),
    ),
)
```

The host is the sole writer of this bounded terminal-facing projection. Feed its
existing prompt/command events into `recordPromptStart`, `recordPromptEnd`,
`recordCommandStart`, and `recordCommandFinished`; publish the validated directory
through `recordCurrentWorkingDirectory`. The IDE keeps its own authoritative
shell model, scripts, and protocol parser. KetraTerm does not reconstruct another
history from OSC, replace host directories, or require synthetic terminal bytes.

Consumers receive `session.shellIntegrationState: TerminalShellIntegrationView`.
It exposes the live query, primitive-copy and observation contract without record
or clear operations. The existing producer state implements that role directly;
the host retains its own `shellState` to publish. See [reader ownership and ABI](../docs/render-reader-ownership.md).

For host OSC protocols, use the parser extension through normal session assembly:

```kotlin
parserFactory = TerminalOutputParserFactory { sink, clipboardBudget ->
    TerminalParsers.create(sink, clipboardBudget, hostCustomOscHandler)
}
```

The host handler can capture anchors through the session frame reader before later bytes arrive.
See the [ordered callback contract](docs/session-concurrency-locks.md#ordered-custom-osc-handling).
IntelliJ protocol interpretation remains in the host adapter.

Preserve stream order: process the matching output, capture stable primary-buffer
line identities, publish the semantic update, then deliver later bytes. For
example, a host prompt-start callback can capture its anchor with:

```kotlin
var promptLineId = 0L
session.readRenderFrame { frame ->
    promptLineId = frame.lineId(frame.cursor.row)
}
shellState.recordPromptStart(promptLineId)
```

An IDE document offset or current row number is not a line identity. Capture each
command output boundary at its matching event; `recordCommandStart(includeLine)`
specifies whether its anchor belongs to output. Pass the known command text and
directory URI explicitly. The model retains bounded history and supplies the
primitive projection used by decorations, navigation, and output copy. Observe
`revision` for repaint invalidation. `addCommandFinishedListener` and
`addCurrentWorkingDirectoryListener` provide synchronous semantic notifications;
close each registration with its consumer. Directory listeners report future
changed URIs without replay, so read `currentWorkingDirectoryUri()` for the
initial value. Callbacks run outside the model lock and must return promptly.

Publish `commandLine` snapshots with complete known text, a UTF-16 cursor offset,
and zero-based live-grid anchors after matching output/geometry changes. `null`
is authoritative. Standard suggestions consume this source; revision observation
is shared only while collected. The host owns source lifetime and text bounds.
Every assignment advances a synchronized revision, including equal snapshots.
Keep the projection null while pending input makes its state untrustworthy.
The legacy StateFlow-only overload remains readable but does not support
conditional editing.

Capture `session.captureCommandEdit()` before requesting asynchronous suggestions.
Use its `commandLine` to calculate a valid semantic replacement, then call
`session.submitInput(expected, events)` with that same context. Only `ACCEPTED`
permits admission feedback; stale or cancelled contexts send no edit prefix.
An idle writer does not acknowledge remote shell processing. See the
[conditional edit contract](docs/session-concurrency-locks.md#conditional-command-edits)
for model synchronization, cancellation and custom popup integration.

Publish `promptReady` only while the live primary prompt can accept a startup
command. It is separate from initialization and editing availability. A supplied
`startupCommand` uses this readiness through session's ordered writer, after the
current parser batch and responses. Early input cancels it. Session stops
observing readiness when submission finishes or is cancelled. Closing session
stops its observations without clearing host metadata or cancelling host flows.

## KetraTerm's optional producer

Add `ketraterm-shell-integration` and select
`io.github.ketraterm.shell.integration.OscShellIntegration` through the same
`shellIntegration` parameter when launching a shell with compatible hooks. The
standard workspace makes this selection and installs supported launch hooks.
`PtyOptions.shellIntegration` forwards either selection; bare PTY creation has
no producer. Selecting the factory alone does not install shell scripts.

See the [concurrency contract](docs/session-concurrency-locks.md) for producer
callback and lifecycle requirements.
