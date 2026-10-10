# Optional shell integration

`ketraterm-shell-integration` derives shell metadata from OSC 7 and OSC 133
reports. It supplies an optional `TerminalShellIntegrationFactory`; the neutral
model, observation, and input admission contracts belong to
[`ketraterm-session`](../ketraterm-session/README.md).

Add `io.github.ketraterm:ketraterm-shell-integration` alongside your chosen
library entry point. The [root dependency guide](../README.md#using-the-libraries)
explains version alignment with the BOM.

## Composition

Select the producer for a shell configured with compatible prompt hooks:

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.transport.TerminalConnector

fun createShellSession(connector: TerminalConnector): TerminalSession {
    val terminal = TerminalBuffers.create(width = 80, height = 24, maxHistory = 2000)
    return TerminalSession.create(
        terminal = terminal,
        connector = connector,
        shellIntegration = OscShellIntegration.configured(promptMarkersExpected = true),
    )
}
```

Attach consumers before calling `session.start(80, 24)`; the session owns the
connector after successful construction. Close the session when its host lifetime
ends. See the [session lifecycle](../ketraterm-session/README.md#input-and-lifecycle).

Factory selection does not install shell scripts. The launching host installs
the hooks and decides whether they are expected from startup. The standard
[`TerminalWorkspace`](../ketraterm-workspace/README.md) performs that launch
adaptation for supported profiles. Direct PTY users pass the chosen factory
through `PtyOptions.shellIntegration`.

`OscShellIntegration` and `configured(false)` still accept later reports, such as
hooks installed manually or by a nested shell. `configured(true)` sets the
immutable launch expectation exposed by `session.promptMarkersExpected`; it
does not establish prompt readiness or enable command submission.

## Metadata and command text

OSC 7 supplies the reported working-directory URI. OSC 133 markers identify
prompt start (`A`), prompt end (`B`), command start (`C`), and command finish
(`D`, with an optional exit code). The producer anchors records to stable render
line identities and captures command text and directory metadata at command
start. Consumers read `session.shellIntegrationState` for the bounded timeline,
command navigation, completion notifications, and primitive viewport projection.

Command text is reconstructed from terminal cells after the prompt-end marker.
Soft wraps join without a newline; hard line breaks and grapheme text are
preserved. Extraction is bounded to 256 rows and 4096 UTF-16 code units. Missing,
evicted, ambiguous, or oversized content produces unknown text (`null`). The
current producer retains at most 4096 timeline records.

`session.activeShellCommandLine()` returns an immutable snapshot only when the
prompt context is available and the cursor is at the visible command end. Use
`session.activeShellCommandLineRevision` to observe edits; session shares one
tracker while collectors are present and stops it after the last collector
leaves. Read the snapshot after invalidation: revisions may be conflated. Use
the session's conditional-edit APIs when admitting asynchronous suggestions.

## Readiness and trust

A prompt becomes ready after an ordered `A`/`B` pair in the primary screen.
Command markers, alternate-screen output, and a local primary-buffer clear
invalidate that readiness. Session decides when to submit an explicitly configured
`TerminalStartupCommand`, through its normal ordered writer; early user input
cancels the pending submission. Merely receiving markers executes no command.

The host adapter validates and bounds OSC 7 URIs before forwarding them.
`HostPolicy.currentWorkingDirectoryPolicy` can deny directory reports, and
`maxCurrentWorkingDirectoryUriLength` limits accepted URI length. OSC 133 metadata
is interpreted when this producer is selected. These reports describe terminal
output; they do not authenticate the emitting process or authorize filesystem
access. Hosts apply their own policy when using reported paths.

## Ownership and threading

Producer callbacks run under session mutation serialization; keep listeners
short and release their registrations when finished. Metadata reads are thread-safe.

The [session shell contract](../ketraterm-session/docs/session-concurrency-locks.md#selected-shell-integration)
defines notification, frame borrowing, and observation lifetime.

Hosts using their own shell integration depend on `ketraterm-session` and supply
`TerminalShellIntegrationFactory.host`. That selected model remains authoritative;
OSC metadata does not supplement it. Such hosts do not need this module.
