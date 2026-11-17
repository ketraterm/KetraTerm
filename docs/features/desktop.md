# Desktop features

The reusable Swing terminal supplies the shared view. Standalone application
controls and platform integrations are listed separately below.

## Shared terminal view

| Feature | Description |
| --- | --- |
| Text rendering | Java2D rendering, script-run shaping, bidirectional text, font fallback, and Windows color-emoji rasterization. |
| Bidirectional layout | The primary screen reorders bidirectional text within each row. The alternate screen preserves application cell positions, including pane borders, cursors, and selections. Primary-screen application panes do not define separate bidi regions. |
| Presentation selectors | VS15 requests text fonts; VS16 requests emoji rendering. Oversized isolated fallback glyphs scale proportionally within their cell span. Text glyph availability depends on installed fonts. |
| Terminal glyphs | Programmatic drawing of supported box-drawing, block, geometric, all Braille patterns, sextants (`U+1FB00–U+1FB3B`), octants (`U+1CD00–U+1CDE5`), and geometric Powerline separators (`U+E0B0–U+E0BF`) for consistent cell alignment. |
| Appearance | Themes, fonts, line height, signed integer column spacing, padding, cursor styling, and [color customization](../../ketraterm-ui-swing/docs/color-customization.md): focused/inactive selection colors, palette-aware defaults and exact explicit ARGB fills, per-cell reverse video, independent normal/active search fills, foregrounds and inset borders, configurable minimum text contrast, and configurable faint intensity. |
| Scrolling | Smooth row scrolling, precise wheel accumulation, keyboard paging, scrollbars, and selection autoscroll. Admitted typing, editing keys, and paste immediately reveal the live viewport. Local shortcuts, modifiers, focus changes, and key releases preserve scrollback. New output preserves the reader's position by default; scroll on output remains opt-in. |
| Selection | Linear/block selection, wide-cell-aware double-click word/path selection, triple-click row selection, word/row drag extension, command-block selection, Select All, and wrap-aware copying. |
| Search | Search options have explanatory tooltips and Tab/Space access. Command shortcuts belong to each host. Asynchronous text search across the viewport and retained history, including soft-wrapped lines, case sensitivity, whole-word and Java regex options, match highlighting, and next/previous navigation. Whole-word boundaries use Unicode word characters (letters, digits, marks, connector punctuation and join controls). Regex matches within logical lines, trims trailing ASCII spaces, ignores zero-length matches, and reports invalid patterns in the search bar. Literal search retains its streaming matcher; pattern matching buffers one logical line and checks cancellation during matching. New queries search forward from the viewport and wrap through older history. Collection stops after detecting more than 1,000 matches and reports `1,000+`; next/previous navigation still searches the entire retained history, and visible matches are highlighted independently. Exact counts remain available through 1,000 matches. Below the limit, plain literal content refreshes reuse unchanged logical-line results when stable row identities are available. Resize/reflow, history clearing, source/buffer changes, and query changes invalidate reuse. |
| Hyperlinks | OSC 8 and detected links, hover styling, activation policies, context actions, and custom detectors. |
| Clipboard interaction | Copy/paste, optional copy on selection, and configurable clipboard/primary-selection middle-button paste. |
| Mouse routing | Application reporting, application-requested Shift capture (XTSHIFTESCAPE), and mode 1007 alternate-screen wheel-to-arrow input, bounded by host reporting/wheel settings. Shift restores local interaction by default. |
| Search controls | Optional host search chrome with [independent error/no-match counter and field-border colors and query-field selection colors](../../ketraterm-ui-swing-host/README.md#search-colors). Nullable overrides preserve existing defaults; changing colors preserves the current search and query selection. |
| Mouse pointer | Application OSC 22 requests for portable native shapes; activatable links and prompt actions retain pointer precedence. |
| Prompt presentation | Gutter, divider, or hidden decorations; hollow gutter circles and solid divider lines. Both styles remain non-interactive until a command block completes, then enable hover and click-to-select actions with filled gutter circles. Failed-command rails remain visible in both modes, which share a configurable gutter and horizontal text position. Rails and divider rules preserve device-aligned thickness at fractional scales. Independent divider normal/hover colors and normal/failed dot hover and halo colors. [Color settings and defaults](../../ketraterm-ui-swing/docs/color-customization.md#prompt-decorations) preserve geometry and metadata. Requires [shell metadata](shells.md). |
| Sticky command headers | The full captured prompt/input row stays above its output when scrolled away, using the same terminal font, cell size, spacing, colors and styles. Works for live commands and retained history, independently of prompt decoration; primary click reveals the original command and wheel gestures scroll history. Enabled by default with a separate standalone setting. Requires shell metadata with a captured prompt row whose original line is still retained; see the [view contract](../../ketraterm-ui-swing/README.md#sticky-command-headers). |
| Bell | Configurable visual indicator; sound and desktop attention are product-owned. |
| Clear screen | Sends Ctrl+L to request clearing/redrawing by the foreground program; accepted requests return to the live viewport. Clears local selection, search, and suggestions. |
| Closed sessions | Final output remains visible, scrollable, selectable, and searchable. |
| Completion presentation | Embedded suggestion list or host-owned UI; see [availability and sources](completion.md). |
| Accessibility | Standard Swing `AccessibleText` exposes the displayed rows, caret, linear selection, and cell geometry. See the [scope](../../ketraterm-ui-swing/README.md#accessibility). |
| Localization | English catalogs with host-supplied translations and resource overrides. |

Fractional column spacing is unavailable. Resizing a live grid clears physical
selection; resizing closed-session presentation preserves stored content.
See the [Swing guide](../../ketraterm-ui-swing/README.md) for configuration.

## Product integration

| Feature | Standalone |
| --- | --- |
| Sessions | Windows, tabs, and split panes. |
| Local shells | Shared [profile discovery and hooks](shells.md). |
| Settings | TOML configuration and settings dialog, with live appearance updates. |
| Appearance | FlatLaf window chrome and terminal themes. |
| Shortcuts | [Platform shortcuts](../../ketraterm-app/docs/user-guide.md#keyboard-scrolling-and-selection), pane-local font zoom/reset, overlapping history paging, and history/live jumps. Window actions are scoped to their owning app window. Ctrl+Space passes through when no editable shell completion context is available. |
| Initial window size | Fits the preferred grid to the monitor work area before shell output starts; saved dimensions are preserved. |
| Open terminal here | Open another terminal at the current local directory. |
| Tab titles | Custom, application, foreground-process, directory, and profile titles. |
| Tab colors | User-selected tab accent colors with reset. |
| Close confirmation | Confirms termination when shell metadata reports a running command, including grouped pane/window closure. |
| Failed-session presentation | Retains output with a status banner after nonzero or unknown-status exits and transport failure; scrolling, copy, search, and explicit close remain available after process cleanup. Successful exits close normally. |
| Tab restoration | No persisted workspace restoration. |
| Process environment | Uses the configured process environment. |
| Link navigation | Host URL/file actions. |
| Command output | Copy and file export of retained output. |
| Clipboard permissions | Session-bound consent dialogs; explicit unsafe pastes have a separate confirmation with an escaped preview. |
| Bell handling | Independent audible/visual settings with bounded, coalesced feedback at most every 500 ms per window; urgency requests desktop attention where supported, and pop-on-bell raises the window. |
| Notifications | Terminal-originated desktop notifications through the system tray where supported, subject to terminal-output permissions. |
| Command-finished alerts | Opt-in desktop alerts for unfocused panes after a configurable minimum command runtime; initially 10 seconds. Requires shell metadata; see the [alert contract](../reference/notifications.md#command-finished-alerts). |
| Application progress | OSC 9;4 percentage/spinning rings in shell-icon slots, selected-window status and per-window taskbar progress where supported. Enabled independently of title permission, with stale reports cleared after five minutes; see the [contract](../reference/protocol.md#progress-reporting). |

Foreground-process titles use a Unix process-group lookup or a Windows
descendant heuristic; they do not identify programs inside SSH or WSL.
Close confirmation relies on shell command markers; it is not a general detector
of every process running in a session.

See the [standalone README](../../ketraterm-app/README.md) and
[plugin README](https://github.com/ketraterm/KetraTerm-IntelliJ/blob/master/README.md) for installation and use.
