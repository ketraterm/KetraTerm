# Configuration ownership and construction

`SwingSettings`, `SwingHostServices`, `PtyOptions`, and
`TerminalWorkspaceOpenOptions` are immutable snapshots with named construction
and update operations. Their public entry points do not enumerate every field.
Small value records such as `SwingPadding` and launch profiles retain their
intentional data-class contracts.

## Ownership

| Setting | Owner |
| --- | --- |
| Fonts, colors, padding, cursor presentation, input interaction | `SwingSettings` |
| Clipboard, font resolver, hyperlink and suggestion services | `SwingHostServices`; service lifetimes remain host-owned |
| Scrollback capacity | Core creation, selected through `PtyOptions.maxHistory` or workspace open options |
| Shell window permissions | Session `HostPolicy` and product window handling |
| Standalone TOML schema, paths, load/save and defaults | Internal application `KetraTermConfig` and `KetraTermConfigManager` |
| IntelliJ preferences, defaults and persistence | IntelliJ settings services |
| Shared settings-control ranges | `SwingTerminalSettingsBounds` in Swing host support; these are not core/renderer limits |

Swing no longer advertises scrollback or shell window permissions as reloadable
UI fields. Binding/reload still applies width policy, palette, cursor and paste
settings. Changing launch preferences affects new sessions. The existing TOML
keys, paths, normalization, permission defaults and atomic publication are retained.

## Kotlin and Java usage

```kotlin
val settings = SwingSettings.create {
    it.font = Font(Font.MONOSPACED, Font.PLAIN, 16)
    it.lineHeight = 1.2f
}
val updated = settings.copy { it.lineHeight = 1.1f }
val services = SwingHostServices.create { it.fontResolver = hostResolver }
val cleared = services.copy { it.fontResolver = null }
```

```java
var services = SwingHostServices.create(b -> b.setFontResolver(hostResolver));
var cleared = services.copy(b -> b.setFontResolver(null));
var draft = SwingSettings.builder();
draft.setLineHeight(1.2f);
var settings = draft.build();
var edited = settings.toBuilder();
edited.setColumns(120);
var updated = edited.build();
```

Callbacks run once, synchronously, and propagate exceptions. Builders are mutable,
caller-confined drafts; they are never retained by snapshots. Validation occurs
at `build`, including the implicit build performed by `create`/`copy`. A failed
update leaves the original unchanged. Published snapshots have value equality
and store their values directly. PTY command/environment and fallback-font lists
are detached at build time; supplied services and producers retain their own
threading/lifecycle contracts. Nullable services can be explicitly cleared.

Default alternate padding derives from the final primary padding and gutter.
A copied builder retains the resolved alternate padding; assign null to request
recalculation. Rendering reads resolved values without consulting a builder.

## Optional host chrome and labels

`SwingTerminalSearchColors` uses the same synchronous `create`/`copy` and builder
pattern for prepared search colors. Build on the host theme's owning thread;
dynamic `Color` subclasses are sampled into detached ARGB values. Pass the result
to `searchBar.refreshColors(colors)`. EDT calls apply immediately; other calls
enqueue the immutable snapshot. Refresh preserves the query and visibility;
no-argument refresh and reopening retain the last supplied colors. Defaults keep
the original dark styling. Theme observation and snapshot replacement belong to
the host, with no theme callback during painting.

`SwingCompletionSuggestionProvider(engine, contextProvider, sourceLabels)` takes
an exact source-ID-to-label map. It copies and bounds labels at construction;
blank values fail with `IllegalArgumentException`. Unknown IDs use neutral
humanization, without removing product prefixes. Display labels never change
source IDs, ranking or feedback. IntelliJ supplies its product labels at registry
composition; standalone needs no overrides. Published Kotlin and Java consumers
exercise both APIs alongside the existing host-owned suggestion target.

## Prototype and migration evidence

Before changing production APIs, an isolated Kotlin/Java prototype exercised
selective late-field construction, explicit null clearing, validation, source-list
mutation and immutable updates. A second model version added the existing real
`modeReportCapabilities` concept and forwarded it through assembly. Retained V1
Kotlin/Java client bytecode ran against V2 without recompilation. The chosen
single `Consumer<Builder>` callback works in both languages without overload
ambiguity or an implicit receiver hiding caller variables. Production consumer
fixtures exercise the actual four snapshots; PTY byte-response tests verify
host mode-report capability propagation.

This is an intentional pre-freeze source and binary migration: replace nonempty
constructors with `create` callbacks, named-argument `copy` with update callbacks,
and destructuring with getters. Zero-argument defaults remain available. Remove
the three obsolete Swing fields and supply their values to the owners above.
Applications importing workspace's product config must own their persistence;
the standalone internal types are not replacement library APIs. Recompile clients.

Future fields add snapshot getters and draft accessors without changing factory,
builder, or update descriptors. Preserve existing property types and defaults;
adding a field still requires semantic tests and ABI/retained-client checks.
See the [compatibility contract](library-compatibility.md).
