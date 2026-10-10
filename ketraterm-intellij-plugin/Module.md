# Module ketraterm-intellij-plugin

An independent IntelliJ Platform product build that consumes local KetraTerm
libraries through a composite. See the [README](README.md) for installation,
requirements, and build commands; this file describes host assembly.

## Role

| Package | Responsibility |
| --- | --- |
| `io.github.ketraterm.intellij` | Resource bundle and shared plugin entry-point support. |
| `.ui` | Tool-window assembly, terminal panes, keymap actions, completion presentation, hyperlinks, and IDE dialogs. |
| `.settings` | Application settings snapshots, project startup commands, theme and font adaptation. |
| `.services` | Project workspace ownership, tab persistence, IDE completion sources, clipboard-client ownership, and notifications. |

The tool-window factory delegates tab ownership to
`KetraTermProjectTerminalService`. That project service creates local workspace
tabs on a background thread, attaches panes on the EDT, and coordinates tab and
project shutdown. Pane cleanup releases only its own listeners and UI adapters.
Failed assembly rolls back resources while preserving the original failure.

Saved tabs contain profile identities, custom titles, and local working-directory
metadata. Restoration resolves current profiles and settings, checks saved paths
off the EDT, and starts a new session when the tab is shown. No process handles,
terminal output, command lines, or environment snapshots are restored.

Application settings publish normalized state and notify every registered
listener after publication. Open panes reload reusable Swing settings and the
session host policy. Clipboard operations retain their owning IDE client context;
reads wait for pane readiness, stay within the session deadline, and are retired
when either owner closes.

The application completion service creates providers for individual workspace
tabs, owns shared learning, and delegates evaluation, filesystem access, and
persistence to the corresponding KetraTerm modules. IDE sources supply project
files, Git data, and Gradle tasks through bounded, request-owned loading.

## Boundary

Parsing, grid state, input encoding, rendering, and PTY process mechanics remain
in shared modules. IDE navigation, project APIs, client identity, settings XML,
keymap integration, and platform disposal belong here. IntelliJ dependencies and
plugin tooling must stay in this build.

The plugin package uses IntelliJ's Kotlin/coroutine runtime and native PTY stack.
The archive check and coroutine ABI test guard parts of that contract; IDE
verification is still needed when changing platform or shared-library versions.

## Current Scope

The actual registrations are defined in
[plugin.xml](src/main/resources/META-INF/plugin.xml). Terminal capability and gap
status remain in the repository's canonical [feature map](../docs/terminal-feature-map.md)
and [gap map](../docs/terminal-feature-gap-map.md).

## Dependencies and local build

The plugin consumes completion, completion-host, completion-persistence,
ui-swing, ui-swing-host, and workspace. IntelliJ supplies the platform APIs and
the bundled Git and Gradle integrations declared in `plugin.xml`.

[settings.gradle.kts](settings.gradle.kts) includes the parent repository through
`includeBuild("..")`. Dependencies in [build.gradle.kts](build.gradle.kts) use
`io.github.ketraterm` coordinates with the repository-derived version; the
composite substitutes local projects. No Maven publication is needed for local
plugin development, including the product-only workspace and persistence modules.

The plugin relies on IntelliJ's Kotlin and coroutine runtime, and its Pty4J/JNA
native stack. The build excludes duplicate runtime artifacts from the plugin
package. Changes to shared dependencies or language/runtime API usage need
verification against the IDE's runtime, as well as normal library checks.
