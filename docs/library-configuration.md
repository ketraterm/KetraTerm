# Configuration ownership and construction

`SwingSettings`, `SwingHostServices`, and `PtyOptions` are immutable snapshots with named construction
and update operations. Their public entry points do not enumerate every field.
Product-only workspace options use the same construction pattern. Small value
records such as `SwingPadding` retain their intentional data-class contracts.

## Ownership

| Setting | Owner |
| --- | --- |
| Fonts, colors, padding, cursor presentation, input interaction | `SwingSettings` |
| Clipboard, font resolver, and hyperlink services | Immutable `SwingHostServices`; service lifetimes remain host-owned |
| Suggestion provider and optional automatic presentation target | EDT `SwingTerminal` setters; engines and popup resources remain host-owned |
| Scrollback capacity | Core creation, selected through `PtyOptions.maxHistory` by local-session hosts |
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

## Session-independent completion construction

Configure completion through the component's lifecycle API, independently of its
immutable environment services. The provider can be installed before or after
binding. Both operations run on the EDT:

```kotlin
// Session first: the host has already started the session.
val terminal = SwingTerminal(settingsProvider, hostServices)
terminal.bind(session)
terminal.setShellSuggestionProvider(provider)
```

```kotlin
// Component first: a framework needs the component during asynchronous startup.
val terminal = SwingTerminal(settingsProvider, hostServices)
terminal.setShellSuggestionProvider(provider)
// Add terminal to the host layout; when session startup completes, on the EDT:
terminal.bind(session)
```

No session forwarding callback or mutable replacement of `SwingHostServices` is
needed. The provider survives `unbind` and rebinding. Automatic observation and
request work follow the current binding, settings, focus, and session lifetime.
Normal completion captures the actual bound session's editing capability before
provider work; rebinding invalidates the old interaction even when both sessions
show identical command text.

```kotlin
terminal.setShellSuggestionProvider(replacementProvider) // Cancels old work.
terminal.refreshShellSuggestions() // External host metadata changed.
terminal.setShellSuggestionProvider(null) // Disables configured provider requests.
terminal.dispose() // Releases view work; the host closes its session separately.
```

`setShellSuggestionTarget(target)` routes automatic requests to a host-owned popup;
null restores embedded presentation. The target receives the captured interaction.
Targets can collect through their own provider or engine without configuring a
terminal provider. Automatic observation stops when neither is installed.
Custom editors supply an `editTarget` per interaction through
`beginShellSuggestionInteraction`; default bound editing requires no host adapter.
An unbound interaction without a custom edit target can display results but cannot
accept an edit. Provider replacement preserves the original source's feedback
observer for every already captured request.

## Optional host chrome and labels

User-facing text lives in UTF-8 `.properties` catalogs. `SwingHostMessages`,
`SwingTerminalMessages`, and `TerminalCompletionMessages` are small bundle facades
for optional host chrome, the completion popup, and generated completion details.
Their `message(key, arguments...)` boundary can also delegate to an embedding
application's message framework. The IntelliJ host delegates to `DynamicBundle`,
using the IDE's UI locale. The standalone application has its own product catalog.

```kotlin
val messages = SwingHostMessages.forLocale(Locale.FRENCH)
val searchBar = SwingTerminalSearchBar(terminal, messages)
```

Supply a custom `ResourceBundle` as the second `forLocale` argument to replace
some or all messages. Missing custom keys use the English catalog. Resource keys
are documented by each owning module's base `.properties` file; locale variants
follow the standard `Bundle_fr.properties` naming convention. Static labels are
resolved during component construction, and complete formatted messages are
resolved when UI state changes. Painting does not perform bundle lookup or message
formatting. Providers capture one locale and can be supplied independently to
different hosts. Recreate the chrome to change its captured language.

Message patterns use JDK `MessageFormat`: arguments are plain text, can be
reordered, and may use number or choice formats. Literal apostrophes in formatted
patterns must be doubled. Static messages without arguments are returned verbatim.
Clipboard consent receives names and Unicode code-point counts, never clipboard
contents; translated labels preserve decision indices and the safe Deny default.

`SwingTerminalSearchColors` uses the same synchronous `create`/`copy` and builder
pattern for prepared search colors. Build on the host theme's owning thread;
dynamic `Color` subclasses are sampled into detached ARGB values. Pass the result
to `searchBar.refreshColors(colors)`. EDT calls apply immediately; other calls
enqueue the immutable snapshot. Refresh preserves the query and visibility;
no-argument refresh and reopening retain the last supplied colors. Defaults keep
the original dark styling. Theme observation and snapshot replacement belong to
the host, with no theme callback during painting.

`SwingCompletionSuggestionProvider(engine, contextProvider, sourceLabels, feedbackHandler)` takes
an exact source-ID-to-label map. It copies and bounds labels at construction;
blank values fail with `IllegalArgumentException`. Unknown IDs use neutral
humanization, without removing product prefixes. Display labels never change
source IDs, ranking or feedback. IntelliJ supplies its product labels at registry
composition; standalone uses the bundled source labels. Published Kotlin and Java consumers
exercise both APIs alongside the host-owned suggestion target. The optional
feedback observer is captured with the source by `open(request)`. Opening a
terminal request snapshots `contextProvider` synchronously on the EDT; keep that
supplier cheap and return immutable metadata. Direct `suggestions(request)` calls
snapshot context in their caller's thread. Engine work starts on stream collection.

`TerminalCommandSpecs.defaults(locale)` builds a localized immutable command
catalog once, before engine construction. `defaults(bundle)` supports partial
custom resources, and `defaults { key, english -> ... }` integrates another message
framework. Keys use canonical command paths, option tokens, and original argument
names. Localization changes descriptions and argument display labels while
preserving insertion tokens, aliases, ranking, replacement ranges and source IDs.

## Prototype and migration evidence

Before changing production APIs, an isolated Kotlin/Java prototype exercised
selective late-field construction, explicit null clearing, validation, source-list
mutation and immutable updates. A second model version added the existing real
`modeReportCapabilities` concept and forwarded it through assembly. Retained V1
Kotlin/Java client bytecode ran against V2 without recompilation. The chosen
single `Consumer<Builder>` callback works in both languages without overload
ambiguity or an implicit receiver hiding caller variables. Production consumer
fixtures exercise the three supported snapshots; product tests cover workspace
options. PTY byte-response tests verify host mode-report capability propagation.

This is an intentional pre-freeze source and binary migration: replace nonempty
constructors with `create` callbacks, named-argument `copy` with update callbacks,
and destructuring with getters. Zero-argument defaults remain available. Remove
the three obsolete Swing fields and supply their values to the owners above.
Embedding applications own their preferences and persistence; standalone's
internal configuration types are not replacement library APIs. Recompile clients.

Future fields add snapshot getters and draft accessors without changing factory,
builder, or update descriptors. Preserve existing property types and defaults;
adding a field still requires semantic tests and ABI/retained-client checks.
See the [compatibility contract](library-compatibility.md).
