# Library configuration

Configuration follows the object that owns the behavior. Product preferences
and persistence belong to the embedding application.

## Ownership

| Configuration | Owner |
| --- | --- |
| Fonts, palette, padding, spacing, cursor, and interaction | `SwingSettings`. |
| Clipboard, deferred paste, font resolution, and hyperlink services | `SwingHostServices`; supplied services remain host-owned. |
| Suggestion provider and presentation target | EDT `SwingTerminal` setters. |
| History capacity and initial terminal dimensions | Core creation or local-session `PtyOptions`. |
| Terminal-originated actions and replies | Session `HostPolicy`. |
| Shell process and environment | PTY options or the host's connector. |
| Shell metadata | A selected built-in or host-owned producer. |
| Settings files and application defaults | The standalone or IDE product. |

Binding/reloading a Swing view also applies its palette, width, cursor, and paste
settings to the session. Launch settings affect new sessions. See
[binding ownership](../../ketraterm-ui-swing/README.md#binding-and-configuration-ownership).

## Immutable settings

`SwingSettings`, `SwingHostServices`, and `PtyOptions` support
`create(Consumer)`, `copy(Consumer)`, `builder()`, and `toBuilder()`.
Callbacks run synchronously. Builders are caller-confined drafts; validation
occurs at build, and failed updates leave the original snapshot unchanged.
Collection values are detached; supplied services keep their own lifetimes.
Nullable services can be cleared explicitly.

Default alternate-screen padding derives from primary padding and the gutter.
A copied builder retains resolved padding; assigning null requests recalculation.

Use the current examples in the [Swing README](../../ketraterm-ui-swing/README.md)
and [PTY README](../../ketraterm-pty/README.md).

## Session-independent completion construction

Install a provider with `setShellSuggestionProvider` on the EDT, before or after
binding. Provider configuration survives rebinding; active requests and editing
authority follow the current binding. Null removes the configured provider.
`refreshShellSuggestions()` re-evaluates external context changes.

`setShellSuggestionTarget` routes automatic requests to a host-owned surface.
Explicit interactions can instead supply a custom editing target. The default
bound-session target validates request-time context before accepting an edit.
See [completion composition](../../ketraterm-ui-swing-host/README.md#completion-bridge)
for the provider adapter and [Swing integration](../../ketraterm-ui-swing/README.md)
for interaction/presentation APIs.

## Optional host chrome and labels

The shipped message catalogs are English. `SwingHostMessages`,
`SwingTerminalMessages`, and `TerminalCompletionMessages` accept a locale,
partial resource overrides, or host message lookup. Missing keys fall back to
English. IntelliJ uses its platform message system.

Message patterns use JDK `MessageFormat`; formatted literal apostrophes must
be doubled. Components capture their message provider at construction, so
recreate chrome to change that captured language.

Search chrome accepts immutable `SwingTerminalSearchColors` snapshots.
Completion adapters accept source-ID-to-display-label maps. Neither changes
terminal data, source identity, ranking, or feedback routing. Localized command
catalogs change descriptions while preserving command and option tokens.

See [host localization and settings](../../ketraterm-ui-swing-host/README.md#localization-and-host-settings)
and [completion customization](../../ketraterm-completion/README.md#customize-the-engine).
