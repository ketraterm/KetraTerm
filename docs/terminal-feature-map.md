# Feature map

KetraTerm provides a shared terminal engine, Kotlin/Java embedding libraries,
a standalone desktop app, and an IntelliJ plugin. The catalogs below describe
implemented capabilities and their host-specific limits.

| Feature set | What it covers |
| --- | --- |
| [Terminal emulation](features/terminal.md) | Screen operations, Unicode, colors, input protocols, host commands, and queries. |
| [Shell support](features/shells.md) | Automatic discovery, prompt hooks, working directories, startup commands, and completion dialects. |
| [Desktop and IDE](features/desktop.md) | Rendering, selection, search, links, tabs, settings, and product integration. |
| [Completion](features/completion.md) | Availability, built-in commands, dynamic sources, matching, and learning. |
| [Library embedding](features/embedding.md) | Headless sessions, custom transports, rendering, host services, and extension points. |

Start with [library setup](../README.md#using-the-libraries), the
[standalone app](../ketraterm-app/README.md), or the
[IntelliJ plugin](../ketraterm-intellij-plugin/README.md).
Remaining work and intentional exclusions belong in the [gap map](terminal-feature-gap-map.md).

See the [documentation index](README.md) for usage guides and detailed references.
