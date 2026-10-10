# Desktop features

The standalone app and IntelliJ plugin use the same Swing terminal. Product
controls and platform integrations are listed separately below.

## Shared terminal view

| Feature | Description |
| --- | --- |
| Text rendering | Java2D rendering, script-run shaping, bidirectional text, font fallback, and Windows color-emoji rasterization. |
| Terminal glyphs | Programmatic drawing of supported box-drawing, block, and geometric characters for consistent cell alignment. |
| Appearance | Themes, fonts, line height, signed integer column spacing, padding, cursor styling, and selection colors. |
| Scrolling | Smooth row scrolling, precise wheel accumulation, keyboard paging, scrollbars, and selection autoscroll. |
| Selection | Linear/block selection, double-click word selection, triple-click row selection, command-block selection, Select All, and wrap-aware copying. |
| Search | Asynchronous text search across the viewport and retained history, including soft-wrapped lines, case sensitivity, match highlighting, and next/previous navigation. |
| Hyperlinks | OSC 8 and detected links, hover styling, activation policies, context actions, and custom detectors. |
| Clipboard interaction | Copy/paste, optional copy on selection, and configurable clipboard/primary-selection middle-button paste. |
| Mouse routing | Application reporting, Shift override for local interaction, and independently configurable alternate-screen wheel-to-arrow input. |
| Prompt presentation | Gutter, divider, or hidden decorations; requires [shell metadata](shells.md). |
| Bell | Configurable visual indicator; sound and desktop attention are product-owned. |
| Clear screen | Sends Ctrl+L to request clearing/redrawing by the foreground program; clears local selection, search, and suggestions. |
| Closed sessions | Final output remains visible, scrollable, selectable, and searchable. |
| Completion presentation | Embedded suggestion list or host-owned UI; see [availability and sources](completion.md). |
| Localization | English catalogs with host-supplied translations and resource overrides. |

Fractional column spacing is unavailable. Resizing a live grid clears physical
selection; resizing closed-session presentation preserves stored content.
See the [Swing guide](../../ketraterm-ui-swing/README.md) for configuration.

## Product integration

| Feature | Standalone | IntelliJ plugin |
| --- | --- | --- |
| Sessions | Windows, tabs, and split panes. | Project tool-window tabs. |
| Local shells | Shared [profile discovery and hooks](shells.md). | Shared profile discovery and hooks. |
| Settings | TOML configuration and settings dialog, with live appearance updates. | IDE application settings with live updates, and project startup commands. |
| Appearance | FlatLaf window chrome and terminal themes. | IDE theme/font integration and native UI components. |
| Shortcuts | Built-in platform shortcuts. | Active IDE keymap integration. |
| Open terminal here | Open another terminal at the current local directory. | Project View, editor, editor-tab, and terminal context actions. |
| Tab titles | Custom, application, foreground-process, directory, and profile titles. | Same title sources. |
| Tab colors | User-selected tab accent colors with reset. | No corresponding product control. |
| Close confirmation | Confirms termination when shell metadata reports a running command, including grouped pane/window closure. | Confirms tab closure when shell metadata reports a running command. |
| Tab restoration | No persisted workspace restoration. | Remembers order, selected tab, profile, custom title, and local directory; starts restored shells lazily. |
| Project JDK | Uses the configured process environment. | Optional project JDK `JAVA_HOME` and `PATH` injection for new local sessions. |
| Link navigation | Host URL/file actions. | IDE console filters, project files, and editor navigation. |
| Command output | Copy and file export of retained output. | Copy and host actions for retained output. |
| Clipboard permissions | Session-bound consent dialogs. | Consent and clipboard access bound to the originating IDE client. |
| Bell handling | Independent audible/visual settings; urgency requests desktop attention where supported, and pop-on-bell raises the window. | Visual bell. |
| Notifications | Terminal-originated desktop notifications through the system tray where supported. | IDE notifications. Both products apply terminal-output permissions. |

Foreground-process titles use a Unix process-group lookup or a Windows
descendant heuristic; they do not identify programs inside SSH or WSL. Restored
IntelliJ tabs start new processes and do not restore output or running commands.
Project JDK injection excludes WSL launchers and WSL SDK paths.
Close confirmation relies on shell command markers; it is not a general detector
of every process running in a session.

See the [standalone README](../../ketraterm-app/README.md) and
[plugin README](../../ketraterm-intellij-plugin/README.md) for installation and use.
