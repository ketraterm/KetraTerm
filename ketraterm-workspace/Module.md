# Module ketraterm-workspace

Shared product infrastructure for host-neutral profiles, local terminal tabs,
and workspace lifecycle. This module is consumed by the standalone application
and IntelliJ integration; it is not a supported published library entry point.
The [README](README.md) describes construction, listener threading, and ownership.

<a id="package-structure"></a>

## Source structure

All types are in `io.github.ketraterm.workspace`.

| Component | Responsibility |
| --- | --- |
| `TerminalWorkspace` | Register and select tabs, launch local sessions, observe closure, and clean up owned sessions. |
| `TerminalWorkspaceTab` | Session identity and live title, directory, color, and process-title preferences. |
| `TerminalWorkspaceListener` | Host-neutral lifecycle, metadata, window-intent, and clipboard callbacks. |
| `TerminalWorkspaceOpenOptions` | Validated immutable initial session options. |
| `TerminalProfile`, `TerminalProfileKind`, `TerminalProfileRegistry` | Launch vocabulary, presentation classification, and built-in profile discovery. |
| `TerminalShellEnvironment` | Host-selected environment overrides and a PATH prefix. |
| Internal shell bootstrappers | Prepare shell startup hooks and environment reapplication without interpreting terminal output. |

## Lifecycle and concurrency

The workspace protects registry and selection state with its own lock. Tab title
sources share a separate lock; current directory publication is safe to read
from host threads. These protections do not serialize listener callbacks onto a
single thread or make arbitrary product state thread-safe.

A supervisor scope owns per-tab observation. Optional presentation observers
fail independently of essential session-close observation. Shell-model listener
registrations are detached on tab removal or session closure. Opening publishes
the tab before session output starts; every open failure cleans up the prepared
session. Removal and workspace shutdown attempt all required cleanup even when
callbacks throw. See the [lifecycle contract](README.md#architectural-role).

## Dependency boundary

`ketraterm-session` is the API dependency. PTY, shell integration, render API, and
coroutines are implementation dependencies. The workspace chooses local launch
wiring, while PTY owns process mechanics and session owns synchronization. UI
views, completion services, preference persistence, and product commands stay in
the launching host.

## Validation

From the repository root, run `./gradlew :ketraterm-workspace:test` for the module
suite. Tests cover profile discovery, tab selection, listener failures, cleanup,
clipboard routing, and shell bootstrap behavior. Native shell integration and
startup-command tests are environment-dependent; inspect their assumptions when
validating changes to launch behavior.
