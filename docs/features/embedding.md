# Library embedding features

KetraTerm exposes Kotlin and Java APIs for headless pipelines and Swing hosts.
Optional modules can be selected independently through the [BOM](../../ketraterm-bom/README.md).

| Capability | Description |
| --- | --- |
| Headless operation | Parse output, maintain grid/history, encode input, and run sessions without Swing. |
| Custom transports | Supply an ordered byte connector; local PTY hosting is optional. |
| Pipeline composition | Use standard assembly or supply compatible core, parser, and input-encoder implementations. |
| Serialized input | Semantic events, exact bytes, paste, and compound edits share bounded session admission and ordering. |
| Lifecycle | Explicit startup, state observation, failure reporting, close, and retained final-frame reads. |
| Rendering | Primitive frame readers, reusable copied caches, and leased publication for independent renderers. |
| Direct core access | Grid mutation, semantic reads, coherent packed input modes, and cluster-capacity queries. |
| Host services | Clipboard, deferred middle-button paste, fonts, links, notifications, and window policy. |
| Metadata observation | Titles, palette changes, hyperlink registry changes, and shell state through host/session contracts. |
| Shell metadata | Optional built-in OSC producer or a host-owned metadata and editing-context model. |
| Completion | Independent engine, custom catalogs/sources, context resolution, ranking, and replay-retention policy. |
| Completion UI | Custom provider, custom presentation, or both, with request-scoped editing and feedback. |
| Swing interaction | Selection read/write/listeners, selected text without clipboard access, local clear, and pixel/cell geometry queries. |
| Configuration | Immutable settings with Kotlin update callbacks and Java builders. |
| Localization | Resource-bundle overrides for terminal chrome and completion descriptions. |
| Distribution | Dependency-only headless/Swing entry points, version alignment, API documentation, and isolated-consumer verification. |

Hosts own their sessions, services, preferences, and persistence. A Swing view
controls one session viewport; independent scrolling views of one process are
not supported. Disposing a view does not close its session.

Start with the [library setup](../../README.md#using-the-libraries),
[configuration guide](../library/configuration.md), and
[compatibility contract](../library/compatibility.md).
