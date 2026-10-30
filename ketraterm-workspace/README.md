# KetraTerm Workspace

`ketraterm-workspace` is shared product infrastructure for local terminal
profiles, tabs, and session ownership. It has no UI toolkit dependency. The
standalone application and IntelliJ plugin adapt its events to their own views
and settings.

This is product infrastructure. External embedders should use the
[library entry points](../README.md#using-the-libraries) and [PTY API](../ketraterm-pty/README.md).

## How to Use

Create a workspace for the host's lifetime. Here, `runHost` owns the UI and event loop:

```kotlin
import io.github.ketraterm.workspace.TerminalProfileRegistry
import io.github.ketraterm.workspace.TerminalWorkspace
import io.github.ketraterm.workspace.TerminalWorkspaceListener
import io.github.ketraterm.workspace.TerminalWorkspaceOpenOptions
import io.github.ketraterm.workspace.TerminalWorkspaceTab

fun runLocalWorkspace(
    listener: TerminalWorkspaceListener,
    runHost: (TerminalWorkspace, TerminalWorkspaceTab) -> Unit,
) {
    TerminalWorkspace(listener).use { workspace ->
        val profile = TerminalProfileRegistry().initialProfile(emptyList())
        val options = TerminalWorkspaceOpenOptions.create { draft ->
            draft.columns = 100
            draft.rows = 30
            draft.maxHistory = 2_000
        }
        val tab = workspace.openTab(profile, options)
        runHost(workspace, tab)
    }
}
```

When `runHost` returns or throws, `use` closes the workspace and its sessions.
`openTab` includes shell preparation and local process creation; hosts should
perform it away from a UI thread. `tabOpened` is the place to bind a renderer or
establish host routing before terminal output starts.

## Architectural Role

`TerminalWorkspace` owns the tab registry, selection, and session-observation
jobs. PTY process and stream mechanics remain in `ketraterm-pty`; terminal
synchronization and input admission remain in `ketraterm-session`.

Opening a tab registers and selects it, calls `tabOpened`, and then starts its
session with its current geometry. Viewport changes accepted during `tabOpened`
are preserved through first output. Consequently, `tabSelected` may arrive before
`tabOpened`. A listener or startup failure removes the prepared tab, closes its
session, and propagates the failure with any cleanup failures suppressed.

- `tabSnapshot()` copies the open-tab list; the tab objects remain live.
- `selectTab(id)` rejects an unknown identity.
- `closeTab(id)` removes the tab and closes its session; an unknown identity is
  ignored. Remaining selection is reported after cleanup.
- `sessionClosed` reports process exit or transport failure. The stopped tab
  remains registered until the host chooses to remove or restart it.
- `close()` rejects new tabs, attempts cleanup for every tab, and cancels workspace
  jobs. It propagates the first failure with later failures suppressed. Repeated
  or reentrant calls return without waiting for an active close to finish.

Optional process-title and startup-notification observers are supervised
separately, so their failures do not stop session-close observation.

## Profiles and launch options

[`TerminalProfile`](src/main/kotlin/io/github/ketraterm/workspace/TerminalProfile.kt)
contains the process command, environment, initial directory, display category,
and optional shell environment or startup command. Command arguments are passed
as a list, rather than split from a command string. Keep the supplied collections
unchanged while the profile is in use.

`TerminalProfileRegistry` discovers built-in profiles in preference order.
`configuredProfile` preserves the requested executable and uses recognized
built-in arguments and display names. `initialProfile(args)` preserves explicit
arguments as a one-off profile; without arguments it selects the first discovered
profile. Discovery and path validation are launch-time work.

`TerminalWorkspaceOpenOptions` configures dimensions, history, shell hooks,
input policy, and host capabilities for new sessions. Use `create`, `copy`, or
`builder` to obtain an immutable snapshot. Construction requires at least
`TerminalBuffers.MIN_WIDTH` (2) columns and one row, before any session is created.

`applySettings(palette, treatAmbiguousAsWide)` changes the palette and width policy
for future writes in open sessions. Other launch options describe new sessions.
Synchronous startup, settings, and cleanup can wait for session work; call them
off UI threads. UI hosts use `openTabAsync`, `applySettingsAsync`, `closeTabAsync`
(or `closeTabsAsync` for a split container), and `closeAsync`. These share one
ordered I/O worker with 64 pending ordinary slots and one cleanup reservation per
owned tab. Duplicate pending tab cleanup is rejected. A `true` return means accepted; the callback
reports completion on the worker. Dispatch UI publication explicitly. Shutdown
has a reserved slot, drains accepted operations, and survives view disposal.
The asynchronous profile supplier runs on the worker, allowing launch preparation
without reading UI state there. The `tabOpened` callback still precedes connector
startup; finish pane binding and its initial settings before returning from that
callback when startup output depends on them. Such a handoff must never hold a
session mutation gate or make the UI wait for the workspace worker.
Products own preference schemas, persistence, and the host actions permitted by
`HostPolicy`; advertise only mode capabilities the host implements.

## Shell metadata ownership

The local session factory selects `OscShellIntegration` and, when enabled,
prepares interactive PowerShell, Bash, zsh, or fish hooks. Explicit script or
command entry points are preserved rather than converted into interactive
launches. Explicit WSL shell launches can receive hooks; startup commands require
a directly configured interactive shell with hooks enabled. Invalid startup
combinations fail before process creation.

`TerminalShellEnvironment` overrides launch environment values and can prepend
one directory to PATH. With working hooks, these selections are reapplied after
shell startup files. Otherwise they affect only the initial process environment.
Startup commands are submitted through the session after shell readiness;
`startupCommandCancelled` reports cancellation caused by earlier user input.

Directory and command-finished notifications come from the session's selected
shell model. `commandFinished` carries each completion's captured metadata,
without replay or conflation. Hosts can capture `commandDurationMillis(recordId)`
from the selected shell view during this callback. `TerminalCommandFinishedAlerts`
shares the products' inclusive duration and permission/focus eligibility policy;
products own preferences, UI dispatch, live focus/lifecycle checks, and delivery.
The tab retains its last observed directory after
session closure. Registrations end on removal or session closure; an already
dispatched callback may finish.

## How to Extend: Custom Tab Listeners

Implement only the `TerminalWorkspaceListener` callbacks your host needs; all
have defaults. Callback threads depend on the source: workspace method callers,
session event producers, or workspace coroutines. The workspace does not dispatch
to Swing's EDT or an IDE UI thread. Schedule UI work on the host's dispatcher.

Terminal metadata callbacks can run under the session mutation lock. Return
promptly, avoid blocking, and schedule work without waiting for UI dispatch or
reentering terminal mutation. Workspace lifecycle notifications run outside the
workspace state lock. Observe the originating `tab` rather than whichever tab is
currently selected.

For title updates queued to a UI thread, read `tab.title` when the queued work
runs and check that the tab still exists. Its precedence is custom title,
application title, foreground process, directory, then profile name. Set
`customTitle`, `color`, or `showForegroundProcessName` for per-tab presentation;
color strings are interpreted by the host.

### Clipboard read routing

`readClipboard(tab, request)` is suspending and resolves the requesting tab
independently of selection. It runs after `tabOpened` returns, on the session I/O
dispatcher without workspace or parser/input locks. If view creation or clipboard
writes were posted asynchronously, await their completion before native access.
Session cancellation and the original read deadline bound the operation. The
default returns `TerminalClipboardReadResult.Unavailable`.

The host also handles allowed clipboard writes and approval prompts. Provider
read failures are audited by the session without clipboard details; they are not
sent to `listenerFailed`. See the [session clipboard contract](../ketraterm-session/README.md)
for reply and cancellation behavior.

## Sub-Documentation

- [Module structure and maintenance](Module.md)
- [Configuration construction and ownership](../docs/library/configuration.md)
- [Standalone profile persistence](../ketraterm-app/docs/profile-config-toml.md)
- [Shell metadata API](../ketraterm-shell-integration/README.md)
