# Module ketraterm-app

Standalone Swing application and product assembly. See [README.md](README.md)
for launching and configuring it.

## Dependencies

| Dependency | Role |
| --- | --- |
| `ketraterm-ui-swing`, `ketraterm-ui-swing-host` | Terminal view and shared host controls. |
| `ketraterm-workspace` | Profiles, tabs, and local session ownership. |
| `ketraterm-completion`, `ketraterm-completion-host`, `ketraterm-completion-persistence` | Evaluation, local sources, and saved learning. |
| FlatLaf and FlatLaf extras | Window theme and desktop UI support. |
| Kotlin coroutines core and Swing | Background work and EDT dispatch. |

These are implementation dependencies. The runtime uses the SLF4J no-op binding.

## Components

`WindowFactory` constructs window chrome. `TabManager` coordinates tabs, splits,
host permissions, and shutdown through `TerminalWorkspace`. Each `TerminalPane`
owns its terminal view, search bar, prompts, and shortcut registrations. Closing
a pane releases those UI resources; closing its workspace tab closes the session.

`KetraTermConfigManager` loads and replaces settings files.
`KetraTermSettings` publishes saved immutable preferences to EDT consumers.
`StandaloneCompletionRegistry` composes the completion modules for a window.

## Configuration

The [configuration reference](docs/profile-config-toml.md) describes the persisted
format. Settings are saved before publication to open panes; process launch
options apply to new sessions.
