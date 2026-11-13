# KetraTerm Library Changelog

Release notes for library consumers and embedders.
Product changes are recorded in the [standalone application changelog](ketraterm-app/CHANGELOG.md).

Released versions: [0.5.0](#050---2026-11-09) · [0.4.0](#040---2026-10-31) ·
[0.3.0](#030---2026-09-23) · [0.2.2](#022---2026-09-11) · [0.2.1](#021---2026-07-14) ·
[0.2.0](#020---2026-07-13) · [0.1.3](#013---2026-07-08) · [0.1.2](#012---2026-07-03) ·
[0.1.1](#011---2026-07-02) · [0.1.0](#010---2026-06-30)

## [Unreleased]

### Headless runtime

- Cap wire REP commands at 65,535 additional copies before sink dispatch, preventing
  a single extreme count from triggering billions of synchronous grid mutations.

- The complete headless pipeline, including PNG graphics decoding, now runs without AWT or
  `java.desktop`. PNG output uses immutable ARGB pixels; Gradle and Maven consumer checks enforce
  desktop independence.

### Completion and shell integration

- Local completion rejects Windows network roots hidden in working-directory URIs,
  including encoded separators, and rejects URI queries and fragments. Explicitly
  typed UNC paths remain available with local directory metadata.

- Added experimental caller-controlled inline suggestions with independent providers, guarded
  acceptance, and configurable keymaps. Popup and inline automation have separate configurable
  delays; manual requests work with automation disabled and take priority over automatic refreshes.

- Hosts can share one suggestion provider and debounce across inline and popup results. Inline
  results publish first. Ghost text uses ordinary terminal painting, Unicode layout, and cursor
  foreground rendering without changing live content; rendering-only callers retain edit-token
  and input ownership.

- Right Arrow accepts visible inline text by default. Passive popups preserve shell Tab completion
  and Enter execution. Deliberate popup browsing hides inline text and enables selected-item
  insertion without execution; fresh results never inherit deliberate selection.

- Hosts can opt into next-word inline acceptance, unbound by default, with echo-validated
  remainder retention and distinct partial feedback.

- Popup path labels preserve navigation segments, quoted paths, and shell expressions in their
  original order. Simple paths retain the name-first layout; both layouts preserve match
  highlighting and full-path tooltips.

- Learned command and path suggestions require two successful executions in the same profile and
  working directory. Observed options remain eligible after one success; recording and ranking
  evidence are unchanged.

- Added `TerminalSession.confirmCommandEdit` and optional buffer-only native confirmation.
  PowerShell suggestion validation reads PSReadLine's buffer without invoking candidate completers;
  full completion requests retain their candidates and editing checks. Fish popup suggestions can
  confirm the editor buffer while native autosuggestions remain visible, preserving existing key
  bindings and exact text/cursor validation.

- Added `TerminalSession.commandOutputText` for bounded headless extraction of a completed command’s
  retained output tail. Results preserve graphemes and logical line breaks, distinguishing caller
  truncation from missing history. Custom producers can implement the optional contract.

### Rendering and interaction

- Swing word selection keeps wide characters intact and does not absorb adjacent paths when clicking
  whitespace or delimiters. Double- and triple-click drags extend by whole words or rows while
  preserving the initial selection when direction changes.

- Swing settings reject non-positive or non-finite primary font sizes while preserving fractional
  sizes and host overrides. See [library defaults](docs/library/configuration.md#library-defaults).

- The default Swing hyperlink handler rejects malformed URIs without throwing and rejects
  unsupported schemes before contacting the desktop.

### Session and PTY

- PTY options and workspace launch profiles preserve empty arguments after the executable, including
  through configuration copies.

- PTY and workspace options reject retained row capacity overflow during construction, before
  process creation.

- Shell-profile discovery honors case-insensitive Windows environment names, accepts quoted Windows
  PATH directories, and skips malformed PATH entries. Unix lookup remains case-sensitive and
  requires execute permission; custom executable predicates retain control over discovery and
  validation.

## [0.5.0] - 2026-11-09

### Packaging and distribution

- Added Maven publications and BOM alignment for `ketraterm-workspace` and
  `ketraterm-completion-persistence`, with public API baselines and isolated Kotlin/Java consumer
  verification. Completion persistence remains experimental.

### Terminal protocols

- Added Kitty ED 22 (`CSI 22 J`) to retain visible text and images in bounded primary scrollback
  before clearing the screen. Custom core writers must implement `moveScreenToHistory`; see the
  [protocol contract](docs/reference/protocol.md#screen-to-history-clear).

- Added REP (`CSI Ps b`) to repeat the preceding grapheme with the current pen, preserving wide
  text, wrapping, margins, insert mode, and scrollback. Custom command sinks and core writers must
  implement `writeRepeatedCluster`; see the
  [REP contract](docs/reference/protocol.md#repeat-character).

- Added HPR (`CSI Ps a`), VPR (`CSI Ps e`), HPB (`CSI Ps j`), and VPB (`CSI Ps k`) as aliases of
  existing cursor movement, preserving count defaults, bounds, margins, origin mode, and wrap
  behavior. See the [cursor alias contract](docs/reference/protocol.md#relative-cursor-aliases).

### Completion and shell integration

- Added experimental command completion with progressive results, contextual ranking, bounded
  learning, and argument hints. The Swing popup supports highlighting, configurable colors and
  shortcuts, and custom providers or UI. Whole-command history requires an argument prefix and has a
  bounded row budget; equivalent PowerShell paths merge. See the
  [completion guide](docs/features/completion.md).

- Native PowerShell requests carry session-assigned IDs that remain unique across prompts.

- Automatic suggestions confirm the editor's buffer independently of prediction redraws and
  unrelated terminal metadata. Suppressed ordinary input preserves the editing context; real edits
  and prompt changes still invalidate acceptance. Stale automatic suggestions refresh even when the
  command text is unchanged.

- Hosts can observe `TerminalSession.commandEditChanges` for capture opportunities and use
  `isCommandEditCurrent` to check retained requests.

- Zsh command discovery no longer stalls when interactive job control is enabled.

- Completion APIs use warning-level Kotlin opt-in. See the
  [completion guide](docs/features/completion.md) for customization and supported sources.

- Render frames and caches expose `cursorPendingWrap` without changing visible cursor coordinates.
  Shell command extraction distinguishes the prompt's final cell from command text at a delayed-wrap
  boundary and preserves intentional leading spaces.

### Rendering and appearance

- Added font-independent rendering for all Braille patterns, 60 sextants, 230 octants, and geometric
  Powerline separators (`U+E0B0–U+E0BF`). Device-aligned geometry preserves segment colors,
  translucent coverage, and cursor repainting across display scales; the extended UTF-8 corpus
  includes complete glyph tables and tiling samples.

- Fixed clipping of isolated fallback glyphs whose visible ink extends left of the origin or beyond
  the advance. Fitting now uses cached ink bounds without changing rendering paths. Default text
  fallbacks prefer installed monospace and script fonts before generic logical fonts; explicit host
  ordering and emoji priority are preserved.

- Fixed fractional-scale seams and cursor repaint artifacts by aligning cell fills, text clips,
  selection/search/link overlays, and cursor shapes to Swing's device-pixel boundaries. Primitive
  glyphs preserve shaped caller clips. Image placements retain fractional coordinates, and native
  emoji rasters use display resolution.

- Added `SwingSelectionStyle` for independent focused/unfocused selection colors, preserved text,
  exact explicit ARGB fills, and cell-based reverse video, plus `SwingSearchMatchStyle` for
  independent normal/active search backgrounds, text colors, and optional inset borders. Untouched
  selection retains its palette-aware visibility fallback; explicit fills remain exact and can be
  reset through the settings builder.

- Prompt decorations support independent divider normal/hover colors and normal/failed dot hover and
  halo colors, with nullable overrides preserving palette-derived defaults.

- Prompt decorations become interactive when a command completes, enabling click-to-select, hover
  styling, filled gutter dots, and failed-command rails. Gutter width now defaults to 10 logical
  pixels with 2 pixels of left padding. Dots, rails, and dividers align consistently at fractional
  display scales.

- Command output ranges and rails now preserve output when command start shares its own prompt line.

- `SwingTerminalSearchColors` adds independent failure/no-match counter and field-border colors and
  query-field selection colors; nullable overrides preserve existing defaults. Existing
  selection/search background accessors preserve the other style roles. Permitted OSC selection
  overrides retain per-role precedence.

- Added renderer-local `minimumContrastRatio` (default `3.0`, `1.0` disables adjustment) and
  `faintOpacity` (default `0.5`), with contrast resolved against composed link/search/selection
  backgrounds. Faint text now blends toward its displayed background. See
  [configuration and migration](ketraterm-ui-swing/docs/color-customization.md).

- Added sticky command headers, enabled by default with an independent setting. Headers repeat the
  retained prompt/input row and reveal the command when clicked, without reserving rows or resizing
  the PTY. Default top padding is now four pixels.

### Scrolling and navigation

- Added `SwingSettings.smoothScrolling`, enabled by default. Library hosts can disable scroll
  animation while retaining precise wheel accumulation; disabling it during a settings reload
  settles pending motion at its destination.

- Typing, editing keys, and accepted paste now immediately reveal the live viewport and cancel
  pending scroll animation. Local shortcuts, modifier/lock/system keys, focus changes, and key
  releases preserve scrollback. `SwingSettings.scrollOnOutput` now defaults to `false`, keeping
  scrolled content anchored while output continues; explicitly enabled output following is
  preserved.

### Session and PTY

- Fixed Windows ConPTY shutdown hanging when a terminal closes during a pending stdout read.
  Shutdown output is discarded while the backend releases its pipe, without delivering bytes or
  remote-exit events to the closed session.

- Fixed intermittent standalone startup exits by establishing the hidden window before asynchronous
  terminal creation. The first window still opens at the configured grid size, and failed or
  cancelled startup releases it.

## [0.4.0] - 2026-10-31

### Breaking changes and migration

- **Breaking for implementers:** Added color-scheme subscriptions (mode 2031), resize reports (mode
  2048), and theme-color resets (OSC 104/110/111/112), respecting reply permissions and output
  ordering. Custom sinks, mode controllers, response channels, buffers, and Kotlin delegates require
  updates and recompilation. See the [protocol contract](docs/reference/protocol.md#change-reports)
  and
  [migration requirements](docs/library/compatibility.md#color-reset-and-change-report-baseline-decision).

- **Breaking:** Configure completion through EDT `SwingTerminal.setShellSuggestionProvider` and
  optional `setShellSuggestionTarget`, replacing the removed completion binding types. Custom
  editors supply an `editTarget` per interaction. `SwingShellSuggestionTarget` moved to
  `ui.swing.suggestion`; call `refreshShellSuggestions` after external metadata changes. Recompile
  consumers; see
  [construction patterns](docs/library/configuration.md#session-independent-completion-construction)
  and [migration notes](docs/library/compatibility.md#verification-and-baseline-changes).

- **Breaking:** Replaced `SwingHostServices.shellSuggestionHandler` with request-scoped edit targets
  and immutable suggestion publications. `tryAccept` returns `SwingShellSuggestionAcceptanceResult`;
  begin/publish/present operations replace `showShellSuggestions`. Candidate constructor/copy
  signatures changed; recompile consumers. See
  [suggestion ownership](ketraterm-ui-swing/README.md#suggestion-request-ownership) and
  [migration notes](docs/library/compatibility.md).

- Replaced `PasteSanitizationPolicy` with `PasteControlPolicy`. Newline handling remains
  host-owned; persisted legacy paste settings migrate automatically.

- Clipboard/title integrations now use `TerminalClipboardPolicy.writePermission` and
  `TerminalTitlePolicy.permission`. Removed origin-specific APIs and clipboard `ALLOWLIST`; old
  split permission settings fall back to the new defaults.

- Added `SwingTerminalSearchAction`, `SwingTerminalSearchBar.performAction`, and
  `setShortcutTextProvider` for host-owned search commands and shortcut hints. **Behavior change:**
  the shared search bar no longer installs Enter, Shift+Enter, or Escape bindings; embedders must
  bind navigation and closing through their own action system.

- `SwingScrollbarAdapter` now implements `AutoCloseable`. Hosts must call `close()` on the EDT when
  its attachment ends. Cleanup is idempotent, releases the scrolling destination, removes only the
  adapter's adjustment listener, and ignores later viewport and adjustment callbacks.

### Terminal protocols

- Added optional `TerminalProgressCommandSink` and OSC 9;4 parsing with title-permission checks.
  Standard sessions expose retained `TerminalProgress` through `progress`, with bounded five-minute
  stale expiry and reset/closure cleanup. Existing sink implementations and constructor signatures
  remain available; see the [progress contract](docs/reference/protocol.md#progress-reporting).

- Added XTVERSION product/build queries with bounded identity replies under terminal-response
  permission. Existing DA and `$TERM` identities are unchanged; custom sinks and response channels
  may implement the new optional query method. See the
  [reply contract](docs/reference/protocol.md#xtversion).

- Expanded XTGETTCAP discovery for scrolling, paste, underline styling, and legacy keyboard
  capabilities. Replies follow active modes and host policy; unsupported formats return failure
  replies. Added DECKPAM/DECKPNM keypad switching while preserving Num Lock typing. Batched requests
  receive bounded individual replies, with atomic malformed-input rejection. See the
  [reply contract](docs/reference/protocol.md#xtgettcap).

- Added inline Kitty graphics for RGB/RGBA and PNG, including bounded compressed/chunked transfers,
  queries, cropping, scaling, deletion, and scrollback integration. Cell-size reports preserve image
  proportions; Swing reuses rasters while scrolling. Custom parser, core, and UI hosts should follow
  the [graphics migration notes](docs/library/compatibility.md#development-migrations).

- Added Kitty color-stack save/restore (OSC 30001/30101), including dynamic cursor/selection
  behavior and bounded nesting. Custom sink/core implementations must add `pushColors()` and
  `popColors()`; see [migration notes](docs/library/compatibility.md#osc-21-color-migration).

- Added Kitty OSC 21 indexed, foreground/background, cursor, and selection colors with ordered
  queries, theme resets, dynamic cursor/selection rendering, and safe unknown-key replies. Custom
  sink/core implementations need the new batch method; see
  [migration notes](docs/library/compatibility.md#osc-21-color-migration).

- Added DEC mode 2027 discovery: DECRQM reports grapheme processing as permanently enabled (`3`).
  Set/reset requests preserve the existing Unicode and width policies; replies obey
  terminal-response permission. See the
  [mode-status contract](docs/reference/protocol.md#mode-status-reports).

- Added OSC 22 mouse pointer shapes. Custom `TerminalCommandSink` implementations must add
  `setPointerShape(TerminalPointerShape?)`; see
  [migration notes](docs/library/compatibility.md#osc-22-sink-migration).

- Corrected restored text styling and character sets across screen switches. Fixed following-text
  overwrites after in-row grapheme width changes; late width changes now have a documented streaming
  policy. Rejected characters no longer redirect later grapheme extensions onto earlier text.

- Made large cursor, tab and scroll commands overflow-safe and bounded. Invalid byte slices are
  rejected before changing state.

- Added ANSI/DEC mode-status queries and expanded xterm key-resource negotiation. Fixed committed
  text being lost or encoded as NUL.

- Expanded CSI commands to 32 parameters and bounded grapheme retention to 32 codepoints. Excess
  parameters reject the command; excess grapheme continuations are discarded without creating extra
  cells.

- Added `TerminalLine.getClusterLength(col)` for allocation-free sizing of reusable direct-read
  cluster buffers. Core lines return the exact stored codepoint count, or zero for non-cluster
  cells; callers keep sizing and copying under one uninterrupted serialization boundary. Scalar-only
  implementations inherit the default, while clustered implementations must supply the count.
  Existing `readCluster` signatures and complete-copy behavior remain unchanged.

- Added `TerminalInputState.withKeyModifierOption` and `withKeyFormatOption` for independent mode
  producers using the stock input encoder. These primitive helpers support every published xterm key
  resource, validate semantic values including modifier disable, and preserve all unrelated bits
  without creating a terminal buffer or snapshot object. Existing packed meanings and decoding APIs
  remain unchanged.

- Added `TerminalCustomOscHandler` through `TerminalParsers.create` for bounded, ordered host OSC
  handling. Hosts can configure the envelope limit; the default is 4 KiB. Session factories preserve
  host services and existing transport failure routing.

### Completion and shell integration

- Made completion initialization independent of session construction. The Swing terminal now owns
  automatic observation, debounce, and cancellation under its bound session; providers can be
  installed before binding and retained across rebinding without forwarding callbacks or separately
  bound editing services.

- Completion acceptance now reports the actual edit admission result, so custom handlers rejecting
  stale or invalid edits cannot emit accepted feedback. Rejection does not penalize learning, and
  feedback retains the originating request and provider token.

- Separated suggestion sources, interaction state, edit admission, and presentation so embedders can
  combine custom providers with the built-in popup or independent Swing/IntelliJ surfaces. Immutable
  publications protect against stale UI actions; selection changes reuse the candidate list.

- Exposed the selected shell producer's immutable `promptMarkersExpected` startup expectation.
  Configured launches reserve gutters at binding; unconfirmed sessions retain metadata-based
  activation without losing the first prompt anchor.

- Shell metadata now updates without transport output, including command completion events.

- Discarded stale suggestions when the command context changes. Closing the session or disabling
  suggestions cancels pending requests and closes popups.

- Added optional `TerminalShellIntegrationView.commandDurationMillis(recordId)` for completed
  commands, using monotonic timing in the built-in shell state. Unknown timing returns null;
  existing metadata constructors, copy methods, and wall-clock timestamps remain compatible. Hosts
  can revalidate deferred notification delivery with the nonblocking
  `TerminalSession.hostNotificationsAllowed` snapshot.

- Added `TerminalCompletionContext.resolve(request, commandSpecs)` with bundled-spec defaults and
  Java overloads. Custom engines can reuse stock context resolution, sources, and projectors without
  reparsing. Resolved context collections reject mutation; scheduling, ranking, and source lifecycle
  remain caller-owned.

- Added immutable `TerminalCompletionContext.precedingArguments`, `precedingPositionalArguments`,
  and `precedingOptionValues(name)` for earlier decoded values in input order. Queries exclude
  active and later words. Empty quoted values consume argument positions; extra `--` words after the
  option terminator remain positional.

- Added a `replayFilter` constructor parameter to `TerminalCompletionLearningStore` to restrict
  plaintext command replay independently of ranking evidence. It applies to successful executions
  and imported snapshots after built-in checks; failures propagate before mutation.
  Use `replayFilter = { false }` to
  disable replay. Existing constructors and defaults remain available.

- Added conditional command edits through `TerminalShellCommandLineState` to reject edits when the
  command context changes.

### Rendering and appearance

- Added configurable `SwingTerminalProgressIcon` and visibility-bound
  `SwingTerminalProgressAnimation` for embedding hosts. Products use colored percentage/spinning
  rings in shell-icon slots, with unchanged tab names. Products always enable progress independently
  of their title setting; embedding hosts can set `HostPolicy.progressPolicy`. Existing JVM
  constructor/copy entry points are retained.

- `SwingSettings.columnSpacing` now accepts negative logical pixels for condensed cells. Text keeps
  its uncondensed glyph-fitting size while painting, selection, mouse coordinates, and resizing
  share the adjusted cell advance. Component construction and settings reload reject nonpositive or
  overflowing geometry; rejected reloads preserve the previous settings. The integer API and zero
  default are unchanged.

- Added gutter, divider, and undecorated prompt presentation through `SwingPromptDecoration`.
  Divider bands share painting and interaction geometry without changing terminal rows or copied
  text; alternate screens suppress prompt decorations.

- Centered the installed alternate-screen grid on both axes, including leftover pixels, while
  retaining configured insets for grid sizing and exact explicit padding overrides.

- Preserve application cursor shapes in unfocused Swing terminals: blocks become thin hollow
  outlines, while bars and underlines remain steady. Device-aligned beams and underlines keep
  thickness consistent across panes at fractional display scales. Focus restores application
  blinking; cursor visibility, wide-cell ownership, bidi placement, and blinking-text behavior are
  preserved.

- Stopped frame-triggered terminal resizing on buffer switches. Default alternate-screen sizing
  retains the primary total insets while presentation centers the installed grid, so physical
  resizing retains the same grid dimensions in either buffer. Explicit alternate-padding overrides
  remain supported.

- Added EDT-only `SwingTerminal.copyCellPositionAt(x, y, destination)` to map rendered coordinates
  into logical columns and frame rows using a reusable `Point`. It respects wide cells, bidi, and
  fractional scrolling. Non-cell hits return false and clear the point to `(-1, -1)`.

### Search, selection, and links

- Made search chrome shrink with its terminal pane, with a compact preferred width and an options
  popup on narrow panes, preventing search controls from extending outside the overlay.

- Added whole-word and Java regular-expression search alongside literal and case-sensitive search.
  Whole-word matching uses Unicode word boundaries; regex searches span soft-wrapped logical lines,
  ignore empty matches, and report invalid patterns in the search bar. Literal searches retain their
  optimized streaming path.

- Reduced scrollback search work and allocations with text-only frame copying, streaming literal
  matching, compact cell-position tracking, and reuse of unchanged logical-line results during plain
  literal content refreshes. Results can appear before the full scan finishes; superseded searches
  are cancelled, and clearing search releases working storage.

- New searches start at the viewport and wrap through older history. Match collection stops after
  detecting more than 1,000 results and displays `1,000+`, while next/previous navigation still
  searches the entire retained history and visible matches remain highlighted independently.

- Fixed a thin orange search-highlight remnant after editing a selected match into a query with no
  results, including at fractional display scales.

- Added localized explanations and current shortcut hints to search controls, with Tab/Space access
  and visible keyboard focus.

- Moved terminal search off the Swing event thread. Results update during output, preserve the
  active match where possible, and discard stale highlights.

- Added `SwingTerminal.setSearchWholeWord` and `setSearchRegex` for programmatic search options, and
  `TerminalRenderFrame.copyTextLine` for copying text and cell geometry without unrelated rendering
  attributes.

- Reworked hyperlink retention and discovery to preserve prepared links through scrolling and focus
  changes, keep ordered filters from consuming unused rows, and validate hover and activation
  against the displayed occurrence. Hosts can configure OSC 8 styles and direct or modifier
  activation through immutable Swing settings; link cursors reflect activation eligibility.

- Reduced allocations when painting unchanged selections. Search and hyperlink detection now ignore
  artificial padding at wide-character wraps; grid resizing clears selections to prevent copying
  unrelated text.

- Added EDT-only `SwingTerminal.selectedText()` for clipboard-independent text extraction, including
  offscreen selections and closed-session output. It shares clipboard-copy semantics for wrapping,
  block selection, wide cells, and grapheme clusters. Unavailable selection returns `null`; a
  nonempty selection can return an empty string.

- Added native Swing selection ranges with read, set, restore, clear, and removable listener
  operations. Stale restoration rejects replaced layouts and bindings. Grid resize still clears
  selection.

### Input and clipboard

- `SwingTerminal.pasteClipboardText` and `clearScreen` now report actual session input admission,
  returning `false` when startup, closure, or queue capacity prevents acceptance. Successful
  admission does not promise transport completion.

- Added `SwingSettings.alternateScreenWheelToArrowEnabled`, enabled by default. Disabling fallback
  uses local viewport scrolling; application mouse reports retain priority, and Shift respects the
  setting. Reloading wheel or mouse-reporting policy clears partial arrow input. Existing
  constructors remain compatible.

- Added deferred middle-click paste and consent through `SwingHostServices.middleClickPasteHandler`.
  EDT `complete(text)` reports admission, consumes the request once, and rejects replaced bindings.
  `SwingSettings.middleClickPasteSource` selects ordinary clipboard (default) or PRIMARY without
  substitution. Mouse priority, Shift routing, and paste protections remain effective; existing
  constructors remain compatible.

- Added detached custom construction and immutable `withShortcut`/`withoutShortcut` updates to
  `SwingTerminalHostShortcutMap`. Unbound actions now return `null` from `shortcut`, including
  valid actions absent from platform defaults; callers must handle unbound results. Conflicting
  keystrokes and unsupported modifier bits are rejected during configuration.

- Added EDT-only `SwingTerminal.pasteText(String)` for host-supplied text, preserving
  clipboard-paste invalidation, session paste policy, ordering, and admission results without
  reading the native clipboard. Hosts can read asynchronously and verify session identity before
  completing the paste on the EDT.

- Added Swing settings for mouse reporting, copy on selection, middle-button paste, and extra column
  spacing. Defaults preserve existing behavior. Hosts retain clipboard and preference ownership.

### Session and PTY

- Added `TerminalSession.start()` to preserve geometry accepted before startup. Workspace tabs now
  retain viewport dimensions and pixel metadata installed during publication; the existing
  explicit-size startup overload remains available.

- `SwingTerminal.resizeSessionToVisibleGrid` reports cancellation when its completion is obsolete,
  including changes after resize admission, so hosts can await the current viewport before starting
  output.

- Serialized parser-requested 80/132-column transport resizing with session disposal, preserving
  transport-before-grid ordering and preventing resizing of a disposed connector.

- Admitted viewport resizes now request render publication even when viewport metadata capture or
  connector resizing fails, preserving the original exception.

- Swing can bind and resize after session exit. It projects retained rows without reflow, preserves
  selection across font changes, and keeps transport closed.

- Added `TerminalSession.tryResizeViewport` for explicit resize admission during closure. Strict
  resize APIs retain their rejection contract.

- Restored focus notifications for terminal applications that request them.

- Preserved buffered output on process exit and the final rendered state on session closure.
  Transports are released once, and workspace cleanup continues after individual failures.

- Improved window resizing and separated logical 80/132-column switching from window-resize
  permission.

- Added `TerminalSession.clearBuffer` and the core `TerminalWriter.eraseBuffer` operation for local
  screen and history clearing. Both preserve the cursor, modes, and inactive buffer. The session
  returns false after closure. Selection, search, and OSC anchors invalidate after clearing.

- Added `TerminalSession.submitBytes` and `submitInput` for ordered byte and semantic input, with
  explicit acceptance or rejection results.

### Security and permissions

- Prevented unsupported status-string queries from reflecting request data into application input.
  Corrected cursor-style and cursor-position replies.

- Added clipboard reads with consent controls. Clipboard and title permissions now apply to the
  entire session, including nested SSH.

- Moved input, paste and terminal replies to an ordered background writer. Protected bracketed paste
  against embedded control sequences.

### Performance

- Batched printable ASCII runs through the parser, host adapter, and core to reduce per-character
  work during large log ingestion. Core writes eligible spans directly into empty cells while
  preserving wrapping, scrolling, attributes, charset mapping, and grapheme continuations across
  input chunks; complex grid operations retain their existing behavior.

- Reduced ASCII output parsing cost by avoiding unnecessary Unicode property searches while
  preserving charset mapping and grapheme assembly. Added a fresh-terminal log ingestion benchmark
  with retained-cell validation and separate parser/core measurements.

- Replaced repeated Unicode grapheme/emoji range searches with a single packed, deduplicated lookup
  reused throughout grapheme assembly. Added a fast boundary check for consecutive ordinary bases,
  exhaustive Unicode 17 property verification, and a separate first-input benchmark; segmentation
  rules and core width policy are unchanged.

- Added optional `TerminalAsciiCommandSink` for synchronous consumption of borrowed printable ASCII
  byte slices. Existing `TerminalCommandSink` implementations continue receiving scalar commands.
  Added `TerminalWriter.writeAscii` with range/content validation before mutation and a scalar
  default for existing writer implementations; `HostCommandAdapter` uses the optimized core path.

### Localization

- Moved reusable search, context-menu, clipboard-consent, and completion-popup labels, tooltips,
  status, and accessibility text into UTF-8 `.properties` catalogs. Search chrome accommodates
  longer translated labels and status messages; message lookup and formatting occur outside
  painting. Clipboard consent retains payload privacy and the safe Deny default.

- Added `SwingHostMessages`, `SwingTerminalMessages`, and `TerminalCompletionMessages` with
  locale-aware resource-bundle factories, English fallback for missing custom keys, and integration
  with host message providers. Added message-aware search, clipboard, context-menu, completion-view,
  source, and engine overloads while preserving existing constructors and factories. See
  [localization configuration](docs/library/configuration.md#optional-host-chrome-and-labels).

- Added `TerminalCommandSpecs.defaults` overloads for locales, resource bundles, and localization
  callbacks. Command descriptions and argument display labels can be translated when constructing
  the catalog while insertion tokens, source IDs, replacement ranges, and ranking remain unchanged.

### Packaging and distribution

- Added headless and Swing dependencies and a Maven BOM to align library versions.

- Added Java constructor overloads for `HostCommandAdapter` and static `TerminalKeyEvent.key`,
  `codepoint`, and `text` factories with trailing-default overloads. Java callers share Kotlin
  defaults and validation; existing full constructors, companion factories, and Kotlin default-call
  entry points remain available.

- Published the supported libraries separately. Workspace and completion persistence remain product
  modules. See [supported boundaries](docs/library/compatibility.md#supported-boundary).

- Added independently usable Kotlin/Java terminal libraries with configurable host integration. See
  [library contracts](docs/library/configuration.md).

## [0.3.0] - 2026-09-23

### Terminal protocols

- Fixed horizontal-margin scrolling moving guard columns and admitting partial rows to history.
  SU/SD, line feeds, reverse index, and wrapping now share the existing span-safe slice movement
  used by IL/DL, while full-width scrolling retains its ring fast path. Added host byte-stream
  coverage and corrected the core property test and independent cursor/wrap model that expected
  whole-row movement.

- Defined the core invalid/unassigned Unicode width contract. Typed scalar and cluster writes reject
  non-scalars before mutation, and literal string writes replace unpaired UTF-16 surrogates. Pinned
  Unicode 17 width defaults preserve reserved CJK ranges, noncharacters, private-use characters, and
  ambiguous replacement widths. Added atomic rejection and byte-split parser-to-core alignment
  coverage.

- Fixed alternate-screen applications leaking cursor shape and blink changes into the primary
  screen. Actual `47`/`1047`/`1049` transitions preserve the primary presentation independently of
  cursor save slots. Default/omitted `DECSCUSR` now restores the configured shape with blinking
  enabled through `TerminalModeController.resetCursorStyle()`; explicit styles retain their
  existing meaning.

- Added parameterless ANSI/SCO cursor save/restore (`CSI s`/ `CSI u`) with DECLRMM-aware margin
  reset and shared DEC cursor/charset state. Added the required
  `TerminalCommandSink.saveCursorOrResetMargins()` operation so embedders resolve mode-dependent
  saves without duplicating core mode state. Parameterized restores and malformed margin forms are
  ignored; Kitty keyboard controls retain separate dispatch.

- DECCOLM now requires explicit host acceptance and synchronizes grid and connector dimensions
  before subsequent output. Standalone shares its existing resize permission and layout checks with
  ordinary grid resize requests; IDE hosts reject column switches.

- Added policy-gated `CSI ?996n` color-scheme queries using explicit host theme metadata, including
  live IDE theme updates.

### Completion and shell integration

- Added optional local PTY foreground-process metadata, observed only while subscribed. Workspace
  titles prefer custom names, application titles, detected executables, then directory/profile
  fallback. Unix uses the foreground process-group leader; Windows uses the newest live descendant
  with bounded discovery. Both products expose a persisted tracking setting.

- Added validated startup commands to session, PTY, and workspace APIs, with once-per-session prompt
  readiness, serialized submission, cancellation on early input, and standalone plus project-local
  IntelliJ configuration.

- Added `TerminalProfile.shellEnvironment` for toolchain overrides at launch and after supported
  shell startup files, enabling IntelliJ project JDK selection.

- Hardened shell startup argument handling and added real-shell coverage for Bash, zsh, fish, and
  PowerShell.

### Search, selection, and links

- Fixed OSC 8 hyperlink IDs being reassigned after hard reset. Retained IDs now stay invalid after
  reset or eviction, and ID exhaustion cannot retarget old links.

- Fixed lost spaces and line breaks in copy/export and command capture. Added `WRAP_PADDING` render
  metadata to preserve text across soft wraps and resize/reflow without new per-frame storage.

- Fixed hyperlink hover across soft-wrapped rows and output updates. OSC 8 links use a dotted
  resting underline; detected links underline on hover, preserving terminal-authored underline
  styles.

- Replaced the 125 ms hyperlink discovery debounce with a single background worker that coalesces
  pending frames and reuses logical-line snapshots and detection results, reducing frame-update
  allocations.

- Added detector context and per-link validation ranges to the hyperlink discovery API: unchanged
  URLs remain active during progress updates, edited destinations invalidate immediately, and
  IntelliJ console filters retain full viewport context.

- Added `HostEventSink`, `PtyEventListener`, and workspace callbacks for effective palette changes
  and OSC 8 registration, eviction, and clearing. Palette callbacks cover OSC, themes, and hard
  reset, suppressing unchanged values. Core/session palette reads are immutable; hyperlink
  resolution is synchronized.

### Scrolling and navigation

- Reused the allocation-free precise-scroll accumulator for alternate-screen wheel-to-arrow
  fallback, retaining fractional row movement until a whole key step is due. Route and session
  changes clear residual input, and key output per event is bounded. Kept AWT's existing accumulated
  integer wheel-click path for active mouse tracking; added focused translation and lifecycle
  regressions.

- Fixed cursor and scrollback viewport drift when resize reflow evicts older rows. Added exact
  retention coverage for history capacity boundaries and repeated alternate-screen resize cycles.

### Security and permissions

- Raised eligible OSC 52 clipboard writes to the host decoded-byte budget, 1 MiB by default. Host
  validation rechecks limits and permissions before effects; queries and denied transfers retain
  existing bounds. Temporary parser storage is released on completion, rejection, cancellation,
  reset, EOF, or callback failure.

- Bounded dynamic-color commands to 256 bytes and DECRQSS requests to 64 bytes under the existing 4
  KiB backstop. Unknown OSC/DCS families stop collecting early. Oversized OSC 8 clears active link
  context without retargeting stored links; EOF discards unfinished DCS.

- Defined malformed UTF-8 handling: titles and notifications replace it; structured metadata rejects
  it, with rejected OSC 8 clearing active link context. OSC 52 validates decoded UTF-8 before
  auditing or consent. EOF discards unfinished OSC; title and notification limits preserve surrogate
  pairs.

### Testing infrastructure

- Replaced wall-clock scheduling assumptions in asynchronous, concurrency, Swing, and native PTY
  tests with virtual time, explicit completion events, and controlled window geometry. Added
  injectable I/O dispatchers at existing session and persistence boundaries, and strengthened the
  test skill's determinism and cleanup requirements.

- Wired the native PTY test opt-in through to test JVMs and preserved PowerShell fixture-script
  quoting with encoded commands.

## [0.2.2] - 2026-09-11

### Breaking changes and migration

- Raised the minimum build and runtime requirement from Java 21 to Java 25. Library consumers must
  provide Java 25 or newer.

- Made Swing settings immutable: padding uses `SwingPadding` instead of AWT `Insets`, and fallback
  font lists use `ImmutableList`. Hosts should convert mutable font lists with `toImmutableList()`; font-cache configuration changes now reliably invalidate cached resolutions. Consolidated
  duplicate string-keyed font LRU implementations while retaining separate bounded caches.

### Rendering and appearance

- Fixed concealed text becoming visible through block cursors, hyperlink foreground overrides, or
  native emoji. ASCII foreground runs now clip glyph ink at style and visibility boundaries.
  Resetting a hidden blink phase now repaints unchanged cursor and blinking-text pixels.

- Aligned bidirectional text with backgrounds, decorations, cursor, selection, search overlays, and
  hit testing. Contextually shaped glyph groups occupy their terminal cells; cursor repaint and
  bounded shaping windows preserve neighboring Arabic forms, combining marks, and ligatures. Mixed
  bidi rows retain native emoji and geometric primitive rendering.

- Unified native emoji dispatch and font-preference classification. Arabic joiners and
  text-presentation selectors no longer give emoji fonts or host emoji overrides precedence over a
  capable primary text font, including platforms whose emoji fonts advertise native substitute
  glyphs.

- Fixed rectangular selection across bidi rows by storing visual horizontal bounds and mapping each
  row for text extraction. Vertical viewport clipping preserves block-selection columns; linear
  selections retain logical-column semantics.

- Fixed global reverse video and relevant reset transitions leaving cached history attributes stale.
  Global render invalidation now covers retained history without walking every history row to mark
  it dirty.

### Scrolling and navigation

- Consolidated smooth-scroll position, animation, and history anchoring. Output rebases active
  motion without restarting its deadline; direct scrollbar dragging applies immediately, and
  translation stays within installed cache coverage while replacement frames are pending. Resize and
  buffer transitions preserve coherent viewport coordinates through `TerminalSession.resizeViewport`
  and its atomic history/discard baseline.

- Reused bidi and repaint metadata capacity across smooth-scroll overscan changes, preserving
  unchanged row layouts. Scrollbar painting retains geometry and palette colors, and handles tracks
  shorter or narrower than the normal thumb size.

### Session and render APIs

- Refactored session and render orchestration around coroutine-based lifecycle and bounded
  latest-frame publication, reducing redundant frame extraction during heavy output while keeping
  Swing/workspace rendering off core read paths.

- Added explicit render-cache source lifetimes and frame availability. Session replacement
  invalidates retained render, bidi, and search data, and an unpublished session displays an empty
  surface. Direct `TerminalRenderCache.accept` consumers must call `reset()` when changing sources;
  allocated storage is retained.

- Added render `contentGeneration` tracking so cursor-only and viewport-only publications can reuse
  active search results without rescanning retained history. Repaint planning includes previous and
  current search highlights, including matches spanning changed and unchanged wrapped rows.

- Fixed public snapshot threading: `currentSelection()` reads selection and viewport together on the
  EDT, while `viewportState()` copies one complete publication under a short monitor. Viewport
  callbacks run outside synchronization, and primitive animation publication and EDT scrollbar reads
  avoid snapshot allocations.

- Clarified rendering and API contracts for logical cluster columns, font resolution, bounded
  shaping, cache lifetimes, and EDT-only `currentSearchState()` and `preferredGridSize()` access.
  Documented synchronous first-use native emoji initialization and the scope of allocation
  measurements; these measurements do not establish whole-frame allocation or latency guarantees.

### Performance

- Prevented ordinary Unicode from initializing the native emoji rasterizer. Warm scalar and cluster
  image-cache lookups reuse primitive keys and slices; successful and unsupported rasterizations
  share a bounded cache, preventing repeated native work for unsupported text until eviction.

- Moved Swing allocation measurements from unit-test counters into the existing JMH suite with GC
  profiling. Unit tests retain cache and rendering semantics, and scrollback/selection fixtures
  control frame publication and release timers reliably. Whole-component painting benchmarks now
  bind, paint, and dispose on the EDT; CI compiles the benchmark harnesses.

## [0.2.1] - 2026-07-14

### Input and keyboard

- Fixed held Backspace and Delete keys so every OS key-repeat event reaches the shell.

- Improved legacy shell and TUI compatibility for Backspace mode, Ctrl-number shortcuts, modified
  base keys, and extended function keys.

### Terminal protocols

- Aligned PTY and device-attribute identity with KetraTerm's implemented VT420-level capabilities.

- Added VT420 rectangular-area checksums (`DECRQCRA`) across the parser, core, and host pipeline.
  Responses are policy-gated, bounded to the active page, and preserve DEC origin/margin semantics.

## [0.2.0] - 2026-07-13

### Input and clipboard

- Fixed multiline paste framing across local PTY sessions: bracketed paste preserves the payload,
  while unbracketed paste uses CR line boundaries so PowerShell and other interactive shells do not
  receive duplicate or reordered continuation input.

- Made selection copy history-stable by resolving absolute row ranges under the session mutation
  lock, preventing stale-cache copies of unrelated scrollback text.

- Fixed selection extraction to preserve soft-wrapped lines and omit trailing empty terminal cells
  without leaving stale clipboard contents.

### Scrolling and selection

- Fixed scrollback admission for top-anchored scroll regions and alternate-screen transitions so
  TUIs using region-aware scrolling no longer get stuck at live rows and can scroll historical
  output correctly.

- Fixed prompt gutter selection in the terminal UI: it now selects the full command block (prompt +
  output) without leaking into the next prompt row.

## [0.1.3] - 2026-07-08

### Search and host integration

- Moved search chrome and shortcut policy into host-owned action wiring.

- Added shared Swing host utilities for floating search UI and default host shortcuts.

- Added host key handling so shortcuts can be handled or passed through to the PTY.

- Added host-owned context menus that respect terminal mouse reporting, with Shift-right-click as a
  menu override.

- Expanded search and shortcut coverage.

## [0.1.2] - 2026-07-03

### Session and host integration

- Added terminal process close confirmation and enhance command lifecycle tracking.

- Added a `scrollOnOutput` configuration policy and UI settings toggle to lock viewport scroll
  position or snap to bottom when new process output arrives.

### Rendering and appearance

- Added host-provided terminal font fallback resolution through `TerminalFontResolver`, including
  IntelliJ integration backed by `ComplementaryFontsRegistry`.

- Added bounded script-run shaping for complex Swing text rendering so Arabic, Indic, and
  connected-script runs are shaped with full run context while the ASCII path stays fast.

- Replaced eager system fallback font retention with a bounded `SystemFontLru` cache to reduce AWT
  native font memory pressure.

### Search, selection, and links

- Added render-cache-bounded IDE hyperlink overlays so host-discovered links can be highlighted and
  activated without scanning during paint, hover, parser, or core hot paths.

- Improved hyperlink rendering with a cleaner solid underline and immediate preservation of
  discovered link highlights while scrolling through cached rows.

- Overhauled `TerminalSelectionController` to copy multi-line selection text across the entire
  terminal history by fetching custom render frames instead of clamping to the active visible
  viewport.

- Fixed first-run TUI wrapping corruption by drawing the scrollback scrollbar inside a reserved
  terminal gutter instead of letting scrollbar visibility change the available terminal width.

## [0.1.1] - 2026-07-02

### Input and keyboard

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

### Initial release

- First public KetraTerm standalone desktop application release.

- Added a native desktop window hosting the KetraTerm terminal component.

- Added support for multiple terminal tabs with custom titles.

- Added keyboard shortcuts for tab navigation (Ctrl+T for new tab, Ctrl+W to close tab, etc.).

- Integrated local pseudo-terminal (PTY) transport for zsh, zli/zsh, bash, sh, PowerShell, and
  cmd.exe.

- Configurable typography, default colors, palettes, and scrollback capacity.
