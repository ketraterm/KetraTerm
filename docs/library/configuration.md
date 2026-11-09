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

## Library defaults

These are embedding defaults, independent of the standalone app's saved preferences.
Hosts choose their own product settings and supply overrides at the owning boundary.

| Area | Default | Host override |
| --- | --- | --- |
| Initial grid | `80 × 24` in `SwingSettings`, `PtyOptions`, and workspace options. Direct core creation requires explicit dimensions. | Set launch dimensions and the view's preferred grid. Once laid out, the view synchronizes the session to its visible grid. |
| Scrollback | `1_000` rows for core creation, local PTY sessions, and workspace sessions; `0` disables history. | Set `maxHistory` before session creation. The view does not choose history capacity. |
| Font | 16-point plain font from installed curated monospace families, with logical `Monospaced` fallback. | Set `SwingSettings.font`; its point size must be finite and positive. Fractional sizes are supported. |
| Glyph fallback | Installed curated fallback fonts followed by logical fonts; asynchronous system fallback discovery enabled. | Replace `fallbackFonts`, disable `useSystemFallbackFonts`, or supply `SwingHostServices.fontResolver`. |
| Cursor | Block, blinking every `600` ms. | Set `cursorShape` and `cursorBlinkMillis`; `0` disables blinking. |
| Scrolling | Smooth scrolling enabled; new output preserves a scrolled reading position. Accepted terminal input and paste return to live output. | Set `smoothScrolling` and `scrollOnOutput`; use viewport methods and host-owned paging shortcuts. |
| Selection | Linear/block and word/row gestures; copy on selection and middle-button paste disabled. | Set `copyOnSelection`, `middleClickPaste`, and `middleClickPasteSource`, or install a deferred paste handler. |
| Application shortcuts | No copy/paste, tab, zoom, or search bindings installed by the reusable view. | Install host actions through `hostKeyHandler` and host key bindings. The optional host shortcut map is explicitly installed. |
| Terminal mouse input | Reporting enabled; alternate-screen wheel-to-arrow fallback enabled when the application permits alternate scrolling. | Set `mouseReportingEnabled` and `alternateScreenWheelToArrowEnabled`. |
| Suggestions | Master switch disabled; no provider installed. | Enable `smartSuggestionsEnabled` and install a provider on the EDT. |
| OSC 52 clipboard | Reads and writes denied; decoded payload bound is 1 MiB if permitted. | Set session `HostPolicy.clipboardPolicy` and supply host execution/consent. |
| Hyperlink activation | Ctrl/Cmd-click; the system handler permits HTTP, HTTPS, and mailto. Detection from ordinary text is disabled. | Replace `hyperlinkHandler`, install a detector, or set the OSC 8 gesture policy. `TerminalHyperlinkHandler.NONE` denies activation. |
| Other terminal controls | Title, hyperlink metadata, working directory, notifications, window requests, palette, progress, and allowlisted terminal replies permitted by `HostPolicy`. | Select permissions for the entire session; callbacks and product actions remain host-owned. |

History capacity limits rows, not total bytes. The reference table is allocated
for the retained row capacity; row objects are allocated in batches, and cell
arrays and cluster payloads depend on width and content. `maxHistory + rows`
must fit in an `Int`; PTY and workspace option construction rejects overflow
before a process can be launched. Hosts should choose dimensions and history
capacity for their memory budget. See the
[grid storage contract](../../ketraterm-core/docs/grid-storage-layout.md#2-off-screen-history-ring-historyring).

Raw PTY connectors accept any positive width because they create no emulator.
Local session factories, workspace options, and Swing settings require at least
two columns so wide characters fit. A one-column session is rejected before launch.

Changing the view's font or cursor default uses `reloadSettings()`; unchanged
cursor settings preserve an application's selected cursor shape. Blink timing
is presentation policy, while the terminal application can control cursor
visibility and blinking eligibility through its modes.

The system clipboard service supports explicit host copy/paste; it does not
grant OSC 52 permission. `pasteControlPolicy` defaults to `PRESERVE`, independently
of bracketed-paste protection. Hosts choose confirmation or filtering for explicit
paste actions. Generic input policy preserves unbracketed paste line endings and
honors newline mode for Enter; local PTY defaults canonicalize those line endings
and Enter to CR. See [PTY input policy](../../ketraterm-pty/README.md#launch-options).

For hosts that do not implement terminal-originated window controls, select
`HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY)` explicitly.
Clipboard and other permissions apply to all output, including nested SSH and
multiplexer sessions. Denying terminal responses also suppresses failure replies.
See [host policy](../../ketraterm-host/README.md#host-policy) for execution ownership
and the [protocol reference](../reference/protocol.md) for collection and reply bounds.

## Immutable settings

`SwingSettings`, `SwingHostServices`, and `PtyOptions` support
`create(Consumer)`, `copy(Consumer)`, `builder()`, and `toBuilder()`.
Callbacks run synchronously. Builders are caller-confined drafts; validation
occurs at build, and failed updates leave the original snapshot unchanged.
Collection values are detached; supplied services keep their own lifetimes.
Nullable services can be cleared explicitly.

Default alternate-screen padding derives from primary padding and reserved decoration space.
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

Search chrome accepts immutable `SwingTerminalSearchColors` snapshots, including
independent error/no-match status and border colors and query-field selection colors;
see [search colors and defaults](../../ketraterm-ui-swing-host/README.md#search-colors).
Terminal selection colors are configured through `SwingSettings.selectionStyle`
and `inactiveSelectionStyle`. `searchMatchStyle` and `searchActiveMatchStyle`
configure match backgrounds, foregrounds, and optional borders independently.
Prompt decorations accept independent divider normal/hover colors and normal/failed
dot hover and halo colors through the `shellIntegration*Color` settings.
`minimumContrastRatio` and `faintOpacity` control
text readability; see [color customization](../../ketraterm-ui-swing/docs/color-customization.md).
Completion adapters accept source-ID-to-display-label maps. Neither changes
terminal data, source identity, ranking, or feedback routing. Localized command
catalogs change descriptions while preserving command and option tokens.

See [host localization and settings](../../ketraterm-ui-swing-host/README.md#localization-and-host-settings)
and [completion customization](../../ketraterm-completion/README.md#customize-the-engine).
