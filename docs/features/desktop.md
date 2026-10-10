# Desktop features

The standalone app and IntelliJ plugin use the same Swing terminal. Product
controls and platform integrations are listed separately below.

## Shared terminal view

| Feature | Description |
| --- | --- |
| Text rendering | Java2D rendering, script-run shaping, bidirectional text, font fallback, and Windows color-emoji rasterization. |
| Appearance | Themes, fonts, line height, signed integer column spacing, padding, cursor styling, and selection colors. |
| Scrolling | Smooth row scrolling, precise wheel accumulation, keyboard paging, scrollbars, and selection autoscroll. |
| Selection | Linear/block selection, command-block selection, Select All, and wrap-aware copying. |
| Search | Asynchronous text search across the viewport and retained history, including soft-wrapped lines. |
| Hyperlinks | OSC 8 and detected links, hover styling, activation policies, context actions, and custom detectors. |
| Clipboard interaction | Copy/paste, optional copy on selection, and configurable clipboard/primary-selection middle-button paste. |
| Mouse routing | Application reporting, Shift override for local interaction, and independently configurable alternate-screen wheel-to-arrow input. |
| Prompt presentation | Gutter, divider, or hidden decorations; requires [shell metadata](shells.md). |
| Bell | Independently configurable audible and visual indicators. |
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
| Shortcuts | Configurable host bindings. | Active IDE keymap integration. |
| Open terminal here | Open another terminal at the current local directory. | Project View, editor, editor-tab, and terminal context actions. |
| Tab titles | Custom, application, foreground-process, directory, and profile titles. | Same title sources. |
| Tab restoration | No persisted workspace restoration. | Remembers order, selected tab, profile, custom title, and local directory; starts restored shells lazily. |
| Project JDK | Uses the configured process environment. | Optional project JDK `JAVA_HOME` and `PATH` injection for new local sessions. |
| Link navigation | Host URL/file actions. | IDE console filters, project files, and editor navigation. |
| Command output | Copy and file export of retained output. | Copy and host actions for retained output. |
| Clipboard permissions | Session-bound consent dialogs. | Consent and clipboard access bound to the originating IDE client. |

Foreground-process titles use a Unix process-group lookup or a Windows
descendant heuristic; they do not identify programs inside SSH or WSL. Restored
IntelliJ tabs start new processes and do not restore output or running commands.
Project JDK injection excludes WSL launchers and WSL SDK paths.

See the [standalone README](../../ketraterm-app/README.md) and
[plugin README](../../ketraterm-intellij-plugin/README.md) for installation and use.
