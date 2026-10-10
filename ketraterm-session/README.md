# KetraTerm Session (`:ketraterm-session`)

`TerminalSession` connects a transport connector to parsing, terminal state,
input encoding, and render publication. It serializes terminal mutation and
orders application input, terminal replies, and startup input through one
bounded writer. It has no dependency on Swing or a PTY implementation.

For local processes, use the [PTY module](../ketraterm-pty/README.md). For an
existing transport, supply a [`TerminalConnector`](../ketraterm-transport-api/README.md)
as shown below. See the [root README](../README.md) for dependency selection.

## Usage

This example runs an already-created connector until closure. `copyFrame` copies
the borrowed frame into storage owned by the renderer; it must return promptly
and retain neither the cache nor its arrays.

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionCloseEvent
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

suspend fun runTerminal(
    connector: TerminalConnector,
    copyFrame: (TerminalRenderCache) -> Unit,
): TerminalSessionCloseEvent = coroutineScope {
    val terminal = TerminalBuffers.create(width = 80, height = 24)
    val session = TerminalSession.create(terminal = terminal, connector = connector)
    val frames = launch {
        session.renderGeneration.collect {
            session.readPublishedFrame(copyFrame)
        }
    }
    try {
        session.start(columns = 80, rows = 24)
        session.requestRender(scrollbackOffset = 0)
        session.state.filterIsInstance<TerminalSessionState.Closed>().first().event
    } finally {
        frames.cancel()
        session.close()
    }
}
```

The connector owns inbound delivery. Do not also feed bytes into the session
from another producer. `start` is synchronous and permits one attempt; it may
block in the connector. Input is accepted only after connector startup succeeds.
Startup failure closes the session and rethrows the failure.

Successful construction transfers exclusive runtime access to the mutable
terminal and connector. Configure the core before assembly; afterward, use
session APIs for input, settings, and frame reads. A failed `create` leaves the
connector with its caller. Session closure cancels session workers but does not
close the supplied dispatchers or cancel consumer collection scopes.

## Input and lifecycle

Use `submitInput` with semantic key, mouse, focus, paste, or text-replacement
events. A list of events is admitted as one operation whose bytes cannot
interleave with later input or replies. `submitBytes` is for trusted, already
encoded host input: it copies the requested slice without paste framing or
sanitization. Existing `encode*` methods use the same admission path and discard
its result.

`TerminalInputAdmission.ACCEPTED` means queue admission. It does not confirm a
connector write or shell execution, and includes empty or mode-suppressed input.
Other results distinguish incomplete startup, closure, and capacity exhaustion.
Capacity exhaustion closes the session without admitting the rejected operation.
Transport failure or closure can interrupt accepted output; bytes are not retried.
See [admission and ordering](docs/session-concurrency-locks.md#admission-and-ordering)
for budgets and conditional editing.

Observe `state` for `Created`, `Running`, and retained `Closed(event)`.
`isClosed` becomes true when shutdown starts; `Closed` is published after cleanup
and final-frame publication have been attempted. State flows do not complete on
closure, so consumers own cancellation of their collectors. Calls to `close`
are idempotent, but a concurrent close can return before the first close finishes.

Retained output remains readable after closure. See
[closed-session presentation](docs/session-concurrency-locks.md#closed-session-presentation).

## Rendering and local operations

`readRenderFrame` borrows live terminal data under mutation serialization.
`readPublishedFrame` borrows the latest copied cache and returns null before the
first publication. Both are safe from any thread, including after closure.
Read or copy only within the callback; do not retain borrowed storage, mutate it,
wait for UI work, close the session, or reenter session mutation.

`renderGeneration` identifies successful publications. `requestRender` replaces
the session's requested viewport; one session has one published viewport.
Independent scroll positions cannot share that publication. A synchronous frame
read with an explicit offset does not change the published viewport.
See [render scheduling](docs/asynchronous-render-coalescing.md).

`resizeViewport` returns the new scrollback offset, history size, and discarded
row baseline together; apply the complete result when preserving scroll position.
Resize may block in the connector and is not a barrier for queued input.
`clearBuffer` clears the active screen and history without sending connector input
or resetting the parser. It returns false after shutdown begins. See the
[clear contract](docs/session-concurrency-locks.md#local-buffer-clear) before
retaining selection or shell anchors across a clear.

## Host-owned shell integration

A plain session has no shell metadata producer. An embedding host can supply its
bounded terminal-facing timeline, versioned editing state, and prompt readiness:

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellCommandLineState
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.flow.StateFlow

fun createHostSession(
    connector: TerminalConnector,
    shellState: TerminalShellIntegrationState,
    commandLine: TerminalShellCommandLineState,
    promptReady: StateFlow<Boolean>,
): TerminalSession = TerminalSession.create(
    terminal = TerminalBuffers.create(width = 80, height = 24),
    connector = connector,
    shellIntegration = TerminalShellIntegrationFactory.host(
        state = shellState,
        commandLine = commandLine,
        promptReady = promptReady,
    ),
)
```

Use a distinct projection per session and publish metadata in output order.
Publish unknown editing state as null. For asynchronous suggestions, capture
`captureCommandEdit()` and submit with `submitInput(expected, events)` so stale
edits are rejected. See [conditional edits](docs/session-concurrency-locks.md#conditional-command-edits)
and the [producer contract](docs/session-concurrency-locks.md#selected-shell-integration).

## KetraTerm's optional producer

Add [`ketraterm-shell-integration`](../ketraterm-shell-integration/README.md) and
select `OscShellIntegration` through `shellIntegration` when launching a shell
with compatible hooks. Factory selection does not install shell scripts.
## Custom assembly

`TerminalSession.create(terminal, renderReader, connector)` supports separate core
and render collaborators describing the same state. Initial frame dimensions
are checked before ownership transfers; matching dimensions alone do not prove
that the collaborators share state.

`inputEncoderFactory` creates independent admission and streaming encoders with
session-owned mode sources, output sinks, and input policy. `parserFactory`
receives the assembled command sink and live clipboard-write budget. Pass both
to `TerminalParsers.create` when adding a custom OSC handler so built-in services
remain wired. Factories must create fresh instances without I/O or background jobs.
See [ordered custom OSC handling](docs/session-concurrency-locks.md#ordered-custom-osc-handling).

The low-level constructor accepts caller-assembled parser, response reader,
render publisher, and host adapter. The caller owns their consistent wiring;
the session does not wrap an already-built parser. Use standard `create` assembly
unless that wiring must be supplied independently.

OSC 52 clipboard reads use an optional `TerminalClipboardReader`. The host owns
consent and native access; session owns permission checks, deadlines, and bounded
reply commitment. A missing provider produces an empty reply when replies are
permitted. See [clipboard lifetime](docs/session-concurrency-locks.md#clipboard-read-lifetime-and-output-commitment).

For maintainer ownership and validation entry points, see [Module.md](Module.md).
