# KetraTerm Library Changelog

Release notes for library consumers and embedders. Product-specific changes are recorded in the [IntelliJ plugin changelog](ketraterm-intellij-plugin/CHANGELOG.md) and [standalone application changelog](ketraterm-app/CHANGELOG.md).

## [Unreleased]

### Improvements and fixes

- Serialized parser-requested 80/132-column transport resizing with session disposal, preserving transport-before-grid ordering and preventing resizing of a disposed connector.
- Admitted viewport resizes now request render publication even when viewport metadata capture or connector resizing fails, preserving the original exception.
- `SwingTerminal.pasteClipboardText` and `clearScreen` now report actual session input admission, returning `false` when startup, closure, or queue capacity prevents acceptance. Successful admission does not promise transport completion.
- Made completion initialization independent of session construction. The Swing terminal now owns automatic observation, debounce, and cancellation under its bound session; providers can be installed before binding and retained across rebinding without forwarding callbacks or separately bound editing services.
- Completion acceptance now reports the actual edit admission result, so custom handlers rejecting stale or invalid edits cannot emit accepted feedback. Rejection does not penalize learning, and feedback retains the originating request and provider token.
- Separated suggestion sources, interaction state, edit admission, and presentation so embedders can combine custom providers with the built-in popup or independent Swing/IntelliJ surfaces. Immutable publications protect against stale UI actions; selection changes reuse the candidate list.
- Moved reusable search, context-menu, clipboard-consent, and completion-popup labels, tooltips, status, and accessibility text into UTF-8 `.properties` catalogs. Search chrome accommodates longer translated labels and status messages; message lookup and formatting occur outside painting. Clipboard consent retains payload privacy and the safe Deny default.
- Added gutter, divider, and undecorated prompt presentation through `SwingPromptDecoration`. Divider bands share painting and interaction geometry without changing terminal rows or copied text; alternate screens suppress prompt decorations.
- Exposed the selected shell producer's immutable `promptMarkersExpected` startup expectation. Configured launches reserve gutters at binding; unconfirmed sessions retain metadata-based activation without losing the first prompt anchor.
- Centered the installed alternate-screen grid on both axes, including leftover pixels, while retaining configured insets for grid sizing and exact explicit padding overrides.
- Batched printable ASCII runs through the parser, host adapter, and core to reduce per-character work during large log ingestion. Core writes eligible spans directly into empty cells while preserving wrapping, scrolling, attributes, charset mapping, and grapheme continuations across input chunks; complex grid operations retain their existing behavior.
- Reduced ASCII output parsing cost by avoiding unnecessary Unicode property searches while preserving charset mapping and grapheme assembly. Added a fresh-terminal log ingestion benchmark with retained-cell validation and separate parser/core measurements.
- Replaced repeated Unicode grapheme/emoji range searches with a single packed, deduplicated lookup reused throughout grapheme assembly. Added a fast boundary check for consecutive ordinary bases, exhaustive Unicode 17 property verification, and a separate first-input benchmark; segmentation rules and core width policy are unchanged.
- Swing can bind and resize after session exit. It projects retained rows without reflow, preserves selection across font changes, and keeps transport closed.
- Added `TerminalSession.tryResizeViewport` for explicit resize admission during closure. Strict resize APIs retain their rejection contract.
- Added headless and Swing dependencies and a Maven BOM to align library versions.
- Preserve application cursor shapes in unfocused Swing terminals: blocks become thin hollow outlines, while bars and underlines remain steady. Device-aligned beams and underlines keep thickness consistent across panes at fractional display scales. Focus restores application blinking; cursor visibility, wide-cell ownership, bidi placement, and blinking-text behavior are preserved.
- Reworked hyperlink retention and discovery to preserve prepared links through scrolling and focus changes, keep ordered filters from consuming unused rows, and validate hover and activation against the displayed occurrence. Hosts can configure OSC 8 styles and direct or modifier activation through immutable Swing settings; link cursors reflect activation eligibility.
- Stopped frame-triggered terminal resizing on buffer switches. Default alternate-screen sizing retains the primary total insets while presentation centers the installed grid, so physical resizing retains the same grid dimensions in either buffer. Explicit alternate-padding overrides remain supported.
- Shell metadata now updates without transport output, including command completion events.
- Discarded stale suggestions when the command context changes. Closing the session or disabling suggestions cancels pending requests and closes popups.
- Restored focus notifications for terminal applications that request them.
- Moved terminal search off the Swing event thread. Results update during output, preserve the active match where possible, and discard stale highlights.
- Reduced allocations when painting unchanged selections. Search and hyperlink detection now ignore artificial padding at wide-character wraps; grid resizing clears selections to prevent copying unrelated text.
- Preserved buffered output on process exit and the final rendered state on session closure. Transports are released once, and workspace cleanup continues after individual failures.
- Corrected restored text styling and character sets across screen switches. Fixed following-text overwrites after in-row grapheme width changes; late width changes now have a documented streaming policy. Rejected characters no longer redirect later grapheme extensions onto earlier text.
- Made large cursor, tab and scroll commands overflow-safe and bounded. Invalid byte slices are rejected before changing state.
- Prevented unsupported status-string queries from reflecting request data into application input. Corrected cursor-style and cursor-position replies.
- Added ANSI/DEC mode-status queries and expanded xterm key-resource negotiation. Fixed committed text being lost or encoded as NUL.
- Added clipboard reads with consent controls. Clipboard and title permissions now apply to the entire session, including nested SSH.
- Moved input, paste and terminal replies to an ordered background writer. Protected bracketed paste against embedded control sequences.
- Improved window resizing and separated logical 80/132-column switching from window-resize permission.
- Expanded CSI commands to 32 parameters and bounded grapheme retention to 32 codepoints. Excess parameters reject the command; excess grapheme continuations are discarded without creating extra cells.

### API changes

- Added `TerminalInputState.withKeyModifierOption` and `withKeyFormatOption` for independent mode producers using the stock input encoder. These primitive helpers support every published xterm key resource, validate semantic values including modifier disable, and preserve all unrelated bits without creating a terminal buffer or snapshot object. Existing packed meanings and decoding APIs remain unchanged.
- Added constructor-supplied `replayFilter` predicates to `TerminalCompletionLearningStore`. Hosts can reject additional plaintext commands or use `replayFilter = { false }` to disable replay while keeping ranking evidence. The filter applies to successful executions and imported snapshots after built-in checks, runs outside the store lock, and propagates failures before mutation. Existing constructors and default replay behavior remain available.
- Added EDT-only `SwingTerminal.selectedText()` for clipboard-independent text extraction, including offscreen selections and closed-session output. It shares clipboard-copy semantics for wrapping, block selection, wide cells, and grapheme clusters. Unavailable selection returns `null`; a nonempty selection can return an empty string.
- Added detached custom construction and immutable `withShortcut`/`withoutShortcut` updates to `SwingTerminalHostShortcutMap`. Unbound actions now return `null` from `shortcut`, including valid actions absent from platform defaults; callers must handle unbound results. Conflicting keystrokes and unsupported modifier bits are rejected during configuration.
- Added EDT-only `SwingTerminal.pasteText(String)` for host-supplied text, preserving clipboard-paste invalidation, session paste policy, ordering, and admission results without reading the native clipboard. Hosts can read asynchronously and verify session identity before completing the paste on the EDT.
- Added Java constructor overloads for `HostCommandAdapter` and static `TerminalKeyEvent.key`, `codepoint`, and `text` factories with trailing-default overloads. Java callers share Kotlin defaults and validation; existing full constructors, companion factories, and Kotlin default-call entry points remain available.
- `SwingScrollbarAdapter` now implements `AutoCloseable`. Hosts must call `close()` on the EDT when its attachment ends. Cleanup is idempotent, releases the scrolling destination, removes only the adapter's adjustment listener, and ignores later viewport and adjustment callbacks.
- **Breaking:** Moved completion lifecycle configuration out of immutable `SwingHostServices`. Use EDT `SwingTerminal.setShellSuggestionProvider` and optional `setShellSuggestionTarget`; default editing captures the actual bound session, and custom editors pass an `editTarget` per interaction. Removed `SwingCompletionBinding`, `SwingCompletionResources`, and `SwingLiveCompletionBinding`; moved `SwingShellSuggestionTarget` to `ui.swing.suggestion`. The completion adapter captures host context and its optional feedback handler in `open`; call `refreshShellSuggestions` after external metadata changes. Recompile consumers and review the pre-freeze ABI/client baseline reset. See [construction patterns](docs/library-configuration.md#session-independent-completion-construction) and [migration notes](docs/library-compatibility.md#verification-and-baseline-changes).
- **Breaking:** Replaced `SwingHostServices.shellSuggestionHandler` with request-captured `shellSuggestionEditTarget` handlers returning `SwingShellSuggestionAcceptanceResult` from `tryAccept`. Added `SwingShellSuggestionInteraction`, immutable snapshots, and `SwingShellSuggestionSource` for independently composed source collection and UI. Feedback observers are captured per source or interaction; request anchors move to presentation, and `showShellSuggestions` is replaced by begin/publish/present operations. Optional opaque feedback tokens change completion and Swing candidate constructor/copy signatures; recompile consumers. See the [completion guide](docs/completion-guide.md) and [migration notes](docs/library-compatibility.md).
- Added `SwingHostMessages`, `SwingTerminalMessages`, and `TerminalCompletionMessages` with locale-aware resource-bundle factories, English fallback for missing custom keys, and integration with host message providers. Added message-aware search, clipboard, context-menu, completion-view, source, and engine overloads while preserving existing constructors and factories. See [localization configuration](docs/library-configuration.md#optional-host-chrome-and-labels).
- Added `TerminalCommandSpecs.defaults` overloads for locales, resource bundles, and localization callbacks. Command descriptions and argument display labels can be translated when constructing the catalog while insertion tokens, source IDs, replacement ranges, and ranking remain unchanged.
- Added optional `TerminalAsciiCommandSink` for synchronous consumption of borrowed printable ASCII byte slices. Existing `TerminalCommandSink` implementations continue receiving scalar commands. Added `TerminalWriter.writeAscii` with range/content validation before mutation and a scalar default for existing writer implementations; `HostCommandAdapter` uses the optimized core path.
- Added Swing settings for mouse reporting, copy on selection, middle-button paste, and extra column spacing. Defaults preserve existing behavior. Hosts retain clipboard and preference ownership.
- Added `TerminalSession.clearBuffer` and the core `TerminalWriter.eraseBuffer` operation for local screen and history clearing. Both preserve the cursor, modes, and inactive buffer. The session returns false after closure. Selection, search, and OSC anchors invalidate after clearing.
- Added native Swing selection ranges with read, set, restore, clear, and removable listener operations. Stale restoration rejects replaced layouts and bindings. Grid resize still clears selection.
- Added `TerminalCustomOscHandler` through `TerminalParsers.create` for bounded, ordered host OSC handling. Hosts can configure the envelope limit; the default is 4 KiB. Session factories preserve host services and existing transport failure routing.
- Added `TerminalSession.submitBytes` and `submitInput` for ordered byte and semantic input, with explicit acceptance or rejection results.
- Added conditional command edits through `TerminalShellCommandLineState` to reject edits when the command context changes.
- Published the supported libraries separately. Workspace and completion persistence remain product modules. See [supported boundaries](docs/library-compatibility.md#supported-boundary).
- Added independently usable Kotlin/Java terminal libraries with configurable host integration. See [library contracts](docs/library-configuration.md).
- Replaced `PasteSanitizationPolicy` with `PasteControlPolicy`. Newline handling remains host-owned; persisted legacy paste settings migrate automatically.
- Clipboard/title integrations now use `TerminalClipboardPolicy.writePermission` and `TerminalTitlePolicy.permission`. Removed origin-specific APIs and clipboard `ALLOWLIST`; old split permission settings fall back to the new defaults.

## [0.3.0] - 2026-09-23

- Fixed horizontal-margin scrolling moving guard columns and admitting partial rows to history. SU/SD, line feeds, reverse index, and wrapping now share the existing span-safe slice movement used by IL/DL, while full-width scrolling retains its ring fast path. Added host byte-stream coverage and corrected the core property test and independent cursor/wrap model that expected whole-row movement.
- Reused the allocation-free precise-scroll accumulator for alternate-screen wheel-to-arrow fallback, retaining fractional row movement until a whole key step is due. Route and session changes clear residual input, and key output per event is bounded. Kept AWT's existing accumulated integer wheel-click path for active mouse tracking; added focused translation and lifecycle regressions.
- Enabled bounded larger OSC 52 writes through production sessions using the active host clipboard decoded-byte budget (1 MiB by default for eligible writes). Parser collection grows on demand only for clipboard write data and releases temporary storage on completion, overflow, cancellation, reset, EOF, or callback failure. Host validation rechecks current limits and permissions before effects; queries, denied transfers, and ordinary metadata retain existing bounds. Added parser storage-lifecycle and session integration tests for exact limits, malformed input, policy changes, and shutdown, plus clipboard JMH workloads.
- Centralized OSC/DCS collection policy: 256-byte dynamic-color commands and 64-byte DECRQSS requests under the existing 4 KiB backstop; other supported families retain their capacity. Unknown families stop collecting before body decoding. Completed oversized OSC 8 clears active link context without changing existing cell destinations, and EOF discards unfinished DCS consistently with OSC. Added byte-boundary, cancellation/recovery, host-state/clipboard/response tests and JMH metadata/rejection workloads. Documented effective raw-versus-decoded clipboard limits.
- Defined the core invalid/unassigned Unicode width contract. Typed scalar and cluster writes reject non-scalars before mutation, and literal string writes replace unpaired UTF-16 surrogates. Pinned Unicode 17 width defaults preserve reserved CJK ranges, noncharacters, private-use characters, and ambiguous replacement widths. Added atomic rejection and byte-split parser-to-core alignment coverage.
- Defined the OSC encoding contract: titles/notifications replace malformed UTF-8; structured metadata rejects it before dispatch, with rejected OSC 8 clearing active link context. OSC 52 validates decoded UTF-8 before permission auditing or prompts and avoids decoding oversized data. Unfinished OSC is discarded at EOF, and title/notification limits preserve surrogate pairs. Added parser and host coverage for malformed input, byte splits, recovery, and metadata state effects.
- Added targeted `HostEventSink`, `PtyEventListener`, and workspace callbacks for effective palette changes and OSC 8 registry registration, eviction, and clearing. Palette callbacks cover OSC, host theme changes, and hard reset without render-frame polling or event snapshots; duplicate values stay silent. Added immutable palette reads through core/session, unchanged-color allocation avoidance, and synchronized hyperlink resolution. Verified ordered/chunked metadata delivery, repeated notifications, listener failure isolation, and tab/session shutdown behavior; consolidated the repeated metadata event gaps.
- Fixed alternate-screen applications leaking cursor shape and blink changes into the primary screen. Actual `47`/`1047`/`1049` transitions preserve the primary presentation independently of cursor save slots. Default/omitted `DECSCUSR` now restores the configured shape with blinking enabled through `TerminalModeController.resetCursorStyle()`; explicit styles retain their existing meaning.
- Replaced wall-clock scheduling assumptions in asynchronous, concurrency, Swing, and native PTY tests with virtual time, explicit completion events, and controlled window geometry. Added injectable I/O dispatchers at existing session and persistence boundaries, and strengthened the test skill's determinism and cleanup requirements.
- Added optional foreground-process metadata from local PTYs and subscription-bound session polling on the I/O dispatcher. Unix uses the PTY foreground process-group leader; Windows uses the newest live descendant, rejecting snapshots with more than 256 candidates or unavailable start times. Shared workspace title precedence is custom name, application title, detected executable, then existing directory/profile fallback. Both products expose a live persisted setting; tracking stops on session close and when unobserved, with no process queries in rendering or byte handling.
- Wired the native PTY test opt-in through to test JVMs and preserved PowerShell fixture-script quoting with encoded commands.
- Added parameterless ANSI/SCO cursor save/restore (`CSI s` / `CSI u`) with DECLRMM-aware margin reset and shared DEC cursor/charset state. Added the required `TerminalCommandSink.saveCursorOrResetMargins()` operation so embedders resolve mode-dependent saves without duplicating core mode state. Parameterized restores and malformed margin forms are ignored; Kitty keyboard controls retain separate dispatch.
- DECCOLM now requires explicit host acceptance and synchronizes grid and connector dimensions before subsequent output. Standalone shares its existing resize permission and layout checks with ordinary grid resize requests; IDE hosts reject column switches.
- Fixed cursor and scrollback viewport drift when resize reflow evicts older rows. Added exact retention coverage for history capacity boundaries and repeated alternate-screen resize cycles.
- Fixed OSC 8 hyperlink IDs being reassigned after hard reset. Retained IDs now stay invalid after reset or eviction, and ID exhaustion cannot retarget old links.
- Added policy-gated `CSI ?996n` color-scheme queries using explicit host theme metadata, including live IDE theme updates.
- Added validated startup commands to session, PTY, and workspace APIs, with once-per-session prompt readiness, serialized submission, cancellation on early input, and standalone plus project-local IntelliJ configuration.
- Fixed lost spaces and line breaks in copy/export and command capture. Added `WRAP_PADDING` render metadata to preserve text across soft wraps and resize/reflow without new per-frame storage.
- Added `TerminalProfile.shellEnvironment` for toolchain overrides at launch and after supported shell startup files, enabling IntelliJ project JDK selection.
- Hardened shell startup argument handling and added real-shell coverage for Bash, zsh, fish, and PowerShell.
- Fixed hyperlink hover across soft-wrapped rows and output updates. OSC 8 links use a dotted resting underline; detected links underline on hover, preserving terminal-authored underline styles.
- Replaced the 125 ms hyperlink discovery debounce with a single background worker that coalesces pending frames and reuses logical-line snapshots and detection results, reducing frame-update allocations.
- Added detector context and per-link validation ranges to the hyperlink discovery API: unchanged URLs remain active during progress updates, edited destinations invalidate immediately, and IntelliJ console filters retain full viewport context.

## [0.2.2] - 2026-09-11

- Raised the minimum build and runtime requirement from Java 21 to Java 25. Library consumers must provide Java 25 or newer.
- Refactored session and render orchestration around coroutine-based lifecycle and bounded latest-frame publication, reducing redundant frame extraction during heavy output while keeping Swing/workspace rendering off core read paths.
- Made Swing settings immutable: padding uses `SwingPadding` instead of AWT `Insets`, and fallback font lists use `ImmutableList`. Hosts should convert mutable font lists with `toImmutableList()`; font-cache configuration changes now reliably invalidate cached resolutions. Consolidated duplicate string-keyed font LRU implementations while retaining separate bounded caches.
- Fixed concealed text becoming visible through block cursors, hyperlink foreground overrides, or native emoji. ASCII foreground runs now clip glyph ink at style and visibility boundaries. Resetting a hidden blink phase now repaints unchanged cursor and blinking-text pixels.
- Aligned bidirectional text with backgrounds, decorations, cursor, selection, search overlays, and hit testing. Contextually shaped glyph groups occupy their terminal cells; cursor repaint and bounded shaping windows preserve neighboring Arabic forms, combining marks, and ligatures. Mixed bidi rows retain native emoji and geometric primitive rendering.
- Prevented ordinary Unicode from initializing the native emoji rasterizer. Warm scalar and cluster image-cache lookups reuse primitive keys and slices; successful and unsupported rasterizations share a bounded cache, preventing repeated native work for unsupported text until eviction.
- Unified native emoji dispatch and font-preference classification. Arabic joiners and text-presentation selectors no longer give emoji fonts or host emoji overrides precedence over a capable primary text font, including platforms whose emoji fonts advertise native substitute glyphs.
- Added explicit render-cache source lifetimes and frame availability. Session replacement invalidates retained render, bidi, and search data, and an unpublished session displays an empty surface. Direct `TerminalRenderCache.accept` consumers must call `reset()` when changing sources; allocated storage is retained.
- Added render `contentGeneration` tracking so cursor-only and viewport-only publications can reuse active search results without rescanning retained history. Repaint planning includes previous and current search highlights, including matches spanning changed and unchanged wrapped rows.
- Fixed rectangular selection across bidi rows by storing visual horizontal bounds and mapping each row for text extraction. Vertical viewport clipping preserves block-selection columns; linear selections retain logical-column semantics.
- Fixed global reverse video and relevant reset transitions leaving cached history attributes stale. Global render invalidation now covers retained history without walking every history row to mark it dirty.
- Consolidated smooth-scroll position, animation, and history anchoring. Output rebases active motion without restarting its deadline; direct scrollbar dragging applies immediately, and translation stays within installed cache coverage while replacement frames are pending. Resize and buffer transitions preserve coherent viewport coordinates through `TerminalSession.resizeViewport` and its atomic history/discard baseline.
- Reused bidi and repaint metadata capacity across smooth-scroll overscan changes, preserving unchanged row layouts. Scrollbar painting retains geometry and palette colors, and handles tracks shorter or narrower than the normal thumb size.
- Fixed public snapshot threading: `currentSelection()` reads selection and viewport together on the EDT, while `viewportState()` copies one complete publication under a short monitor. Viewport callbacks run outside synchronization, and primitive animation publication and EDT scrollbar reads avoid snapshot allocations.
- Clarified rendering and API contracts for logical cluster columns, font resolution, bounded shaping, cache lifetimes, and EDT-only `currentSearchState()` and `preferredGridSize()` access. Documented synchronous first-use native emoji initialization and the scope of allocation measurements; these measurements do not establish whole-frame allocation or latency guarantees.
- Moved Swing allocation measurements from unit-test counters into the existing JMH suite with GC profiling. Unit tests retain cache and rendering semantics, and scrollback/selection fixtures control frame publication and release timers reliably. Whole-component painting benchmarks now bind, paint, and dispose on the EDT; CI compiles the benchmark harnesses.

## [0.2.1] - 2026-07-14

- Fixed held Backspace and Delete keys so every OS key-repeat event reaches the shell.
- Improved legacy shell and TUI compatibility for Backspace mode, Ctrl-number shortcuts, modified base keys, and extended function keys.
- Aligned PTY and device-attribute identity with KetraTerm's implemented VT420-level capabilities.
- Added VT420 rectangular-area checksums (`DECRQCRA`) across the parser, core, and host pipeline. Responses are policy-gated, bounded to the active page, and preserve DEC origin/margin semantics.

## [0.2.0] - 2026-07-13

- Fixed multiline paste framing across local PTY sessions: bracketed paste preserves the payload, while unbracketed paste uses CR line boundaries so PowerShell and other interactive shells do not receive duplicate or reordered continuation input.
- Made selection copy history-stable by resolving absolute row ranges under the session mutation lock, preventing stale-cache copies of unrelated scrollback text.
- Fixed selection extraction to preserve soft-wrapped lines and omit trailing empty terminal cells without leaving stale clipboard contents.
- Fixed scrollback admission for top-anchored scroll regions and alternate-screen transitions so TUIs using region-aware scrolling no longer get stuck at live rows and can scroll historical output correctly.
- Fixed prompt gutter selection in the terminal UI: it now selects the full command block (prompt + output) without leaking into the next prompt row.

## [0.1.3] - 2026-07-08

- Moved search chrome and shortcut policy into host-owned action wiring.
- Added shared Swing host utilities for floating search UI and default host shortcuts.
- Added host key handling so shortcuts can be handled or passed through to the PTY.
- Added host-owned context menus that respect terminal mouse reporting, with Shift-right-click as a menu override.
- Expanded search and shortcut coverage.

## [0.1.2] - 2026-07-03

- Added terminal process close confirmation and enhance command lifecycle tracking.
- Added render-cache-bounded IDE hyperlink overlays so host-discovered links can be highlighted and activated without scanning during paint, hover, parser, or core hot paths.
- Improved hyperlink rendering with a cleaner solid underline and immediate preservation of discovered link highlights while scrolling through cached rows.
- Added a `scrollOnOutput` configuration policy and UI settings toggle to lock viewport scroll position or snap to bottom when new process output arrives.
- Added host-provided terminal font fallback resolution through `TerminalFontResolver`, including IntelliJ integration backed by `ComplementaryFontsRegistry`.
- Added bounded script-run shaping for complex Swing text rendering so Arabic, Indic, and connected-script runs are shaped with full run context while the ASCII path stays fast.
- Replaced eager system fallback font retention with a bounded `SystemFontLru` cache to reduce AWT native font memory pressure.
- Overhauled `TerminalSelectionController` to copy multi-line selection text across the entire terminal history by fetching custom render frames instead of clamping to the active visible viewport.
- Fixed first-run TUI wrapping corruption by drawing the scrollback scrollbar inside a reserved terminal gutter instead of letting scrollbar visibility change the available terminal width.

## [0.1.1] - 2026-07-02

- Fixed AltGr character input and terminal grid corruption on AZERTY keyboards for Windows/Linux.

## [0.1.0] - 2026-06-30

- First public KetraTerm standalone desktop application release.
- Added a native desktop window hosting the KetraTerm terminal component.
- Added support for multiple terminal tabs with custom titles.
- Added keyboard shortcuts for tab navigation (Ctrl+T for new tab, Ctrl+W to close tab, etc.).
- Integrated local pseudo-terminal (PTY) transport for zsh, zli/zsh, bash, sh, PowerShell, and cmd.exe.
- Configurable typography, default colors, palettes, and scrollback capacity.
