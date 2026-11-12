# KetraTerm UI Swing (`:ketraterm-ui-swing`)

`SwingTerminal` is a reusable Swing `JComponent` for a `TerminalSession`. It paints
terminal frames and routes user input through the session. The host supplies the
connection, session lifetime, and optional clipboard, font, hyperlink, and action
services. This module requires JVM 25 and has no PTY or IntelliJ dependency.

## How to Use

Create the view on the Swing Event Dispatch Thread (EDT), bind a session, and add
the component to your layout. Bind before session startup when output must wait
for host registration. Retain the `SwingTerminal` so the host can dispose it when
the view closes. It can also be constructed before the session exists, then bound
later.

<!-- compiled-example:terminal-view -->
```kotlin
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSelectionStyle
import io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import java.awt.Font
import javax.swing.SwingUtilities

fun createTerminalView(session: TerminalSession): SwingTerminal {
    return createUnboundTerminalView().apply { bind(session) }
}

fun createUnboundTerminalView(): SwingTerminal {
    check(SwingUtilities.isEventDispatchThread()) { "Create terminal views on the EDT" }

    val settings = SwingSettings.create {
        it.palette = TerminalTheme.ONE_DARK.createPalette()
        it.font = Font("Cascadia Mono", Font.PLAIN, 15)
        it.columns = 80
        it.rows = 24
        it.selectionStyle = SwingSelectionStyle(background = 0x804B6EAF.toInt(), foreground = 0xFFFFFFFF.toInt())
        it.inactiveSelectionStyle = SwingSelectionStyle(background = 0x404B6EAF)
        it.searchMatchStyle = SwingSearchMatchStyle(background = 0x554B6EAF, border = 0xFF4B6EAF.toInt())
        it.searchActiveMatchStyle = SwingSearchMatchStyle(
            background = 0xFFFFC857.toInt(),
            foreground = 0xFF161616.toInt(),
            border = 0xFFFFFFFF.toInt(),
        )
        it.minimumContrastRatio = 4.5 // Default 3.0; set 1.0 to preserve exact text colors.
        it.faintOpacity = 0.5
        it.shellIntegrationPromptDotHoverColor = 0xFFFFFFFF.toInt()
        it.shellIntegrationFailedPromptDotHoverColor = 0xFFFF8A8A.toInt()
        it.shellIntegrationPromptDotHoverHaloColor = 0x404B6EAF
        it.shellIntegrationFailedPromptDotHoverHaloColor = 0x40E74856
        it.shellIntegrationPromptDividerColor = 0x304B6EAF
        it.shellIntegrationPromptDividerHoverColor = 0xFF4B6EAF.toInt()
    }

    return SwingTerminal(settingsProvider = { settings })
}
```

The font name is a host choice; use a font available in your environment.
See [library defaults and host overrides](../docs/library/configuration.md#library-defaults)
for default geometry, font, cursor, interaction, and permission policy. Primary
font sizes must be finite and positive; invalid sizes fail when settings are built.
Selection, search and prompt decoration colors, terminal color precedence, and migration notes are explained in
[Color customization](docs/color-customization.md).
`columns` and `rows` set the preferred size. Once laid out, the visible cell
dimensions determine the live session's grid and connector size. Settings and
session geometry require at least two columns and one row. A narrower component
retains that minimum grid and clips painting to its bounds.

### Binding and configuration ownership

The host owns the session. `unbind()` and `dispose()` cancel view work without
closing the session or restoring earlier settings or dimensions. Rebinding clears
view state and starts observation of the new session. Dispose the component when
its host closes; close the session separately when its connection should end.

Binding applies the view's ambiguous-width policy, palette, cursor shape, paste
policy, and visible geometry to the session. Return a new immutable `SwingSettings`
snapshot from `settingsProvider`, then call `reloadSettings()` to apply changes.
Unchanged palette and cursor settings preserve application-controlled values.
Builders are mutable construction drafts; `build`, `create`, and `copy` freeze
detached snapshots.

Access Swing state on the EDT unless a method explicitly documents another
threading contract. `bind`, `unbind`, `dispose`, and `reloadSettings` update view
state on the EDT and dispatch there when called elsewhere. Captured session
settings, geometry, and window state run on an ordered worker so session admission
cannot stall the EDT. Paste policy keeps its immediate input-admission behavior.
Use `bind(session, onComplete)` when startup depends on initial settings and
geometry; its EDT callback receives `null` after application or the failure.
Binding completion does not cover later component events. After initial layout,
await `resizeSessionToVisibleGrid(onComplete)` before starting session output.

Call `setHostPolicy(policy, onComplete)` on the view to preserve ordering with
view settings. `resizeSessionToVisibleGrid(onComplete)` reconciles externally
changed session dimensions with captured component bounds. Both report completion
on the EDT; acceptance is not application. Resize success confirms current cell
and pixel geometry with no later geometry request pending. Superseded geometry
or an obsolete binding reports cancellation, including a resize admitted before
the view changed. The bounded queue rejects overflow synchronously;
retrying `reloadSettings` with the same snapshot submits changes not yet accepted,
including geometry, without resetting already accepted palette/cursor settings.
Default completion handlers log operational failures. Session closure remains
the host's responsibility and must run off the EDT.

Use one controlling view per session. Settings, dimensions, and the published
viewport belong to the session; multiple components cannot scroll or configure
it independently. After session closure, the view retains the final grid and
selection. Width changes clip rather than reflow it; height changes expose a
bottom-anchored window over retained rows. Presentation changes do not restart
the session.

The default grid padding reserves four pixels at the top and bottom. The first row
and sticky command header share the same top inset, including while scrolling.
Configure `SwingSettings.padding` to customize these margins.

### Sticky command headers

`stickyCommandHeadersEnabled` defaults to `true`, independently of `promptDecoration`.
When command output reaches the viewport top and its input has scrolled away, one
opaque overlay repeats the captured physical prompt/input row, including the prompt
prefix, directory, command, original cell colors and attributes. It uses the same
terminal font, metrics, spacing, shaping, and fallback as ordinary output. Long rows
clip to the available width; they are never shortened, scaled, or given a separate font.

This applies to live running commands, scrollback, and retained closed-session output,
provided the original prompt row remains in the screen or scrollback. A saved prompt
image does not keep the header visible after that row is erased or trimmed.
Alternate-screen content, prompts still visible at the top, untouched trailing rows,
commands without a captured prompt, and output that would be entirely covered by the
header have no overlay. The overlay reserves no grid space and does not resize the
terminal. A soft ten-pixel shadow fades below the header, with a subtle contact edge
on dark themes; only its opaque row intercepts pointer actions. Primary click reveals
the original command. Wheel gestures over the overlay scroll local history. Covered
cells receive no new selection, hyperlink, context-menu, paste, or application-mouse
gestures.

The standard OSC integration captures the row before command output. Custom shell
views implement `commandOutputRecordIdAtLine` and `commandPrompt` to supply this
presentation; a host using `TerminalShellIntegrationState` can pass an immutable
`TerminalRenderRowSnapshot` to its command-start overload. See the
[shell metadata contract](../ketraterm-shell-integration/README.md) for retention bounds.

### Search

`search(query)` starts a background search; observe `searchState` for results or
failure. Literal, case-insensitive search is the default. Use
`setSearchCaseSensitive`, `setSearchWholeWord`, and `setSearchRegex` to change
independent options. Each setter restarts a nonempty query; `clearSearch()` clears
the query and highlights while retaining options. Calls execute on the EDT or
enqueue there when called from another thread.

Whole-word matching uses Unicode word boundaries. Regex uses Java `Pattern`
syntax and matches one logical line at a time, including soft wraps; it does not
cross hard line breaks. Trailing ASCII spaces are trimmed and empty matches are
ignored. Invalid patterns appear in `searchState.failure`. Pattern matching
buffers one logical line on the worker and checks cancellation during
backtracking. Ordinary literal searches retain their streaming matcher.

### Manual paste

`pasteText(text)` accepts host-supplied text on the EDT without reading a
clipboard. For asynchronous clipboard access, capture the intended binding,
perform the read in host-owned work, and verify that binding on the EDT before
calling `pasteText`. The host must discard results from an obsolete binding.

`pasteClipboardText()` reads the supplied clipboard handler synchronously on
the EDT. Both paste methods use session policy and admission; `true` means
admitted, not delivered. Empty text, an unavailable session, off-EDT calls,
and rejected admission return `false`.

`copyOnSelection` copies a nonempty selection at local primary-button gesture
completion. It defaults to `false` and uses the supplied clipboard handler.
Application mouse reporting takes priority. Shift restores local interaction
unless the application requests Shift capture through XTSHIFTESCAPE.
Clipboard callbacks run on the EDT.

### Middle-button paste

`middleClickPaste` defaults to `false`. When enabled, a local middle-button press
uses `middleClickPasteSource`: `SwingPasteSource.CLIPBOARD` by default, or
`PRIMARY_SELECTION` for native primary-selection text. The built-in path reads
only that source synchronously on the EDT. An unavailable primary selection
never falls back to the ordinary clipboard. Keyboard, menu, and programmatic
clipboard paste still use the ordinary clipboard.

For asynchronous or custom access, install
`SwingHostServices.middleClickPasteHandler`. It receives the source and local
press coordinates on the EDT. Finish once with `request.complete(text)` or
`request.cancel()` on the EDT; there is no fallback after the handler returns.
Completion rejects a closed or changed binding. The host owns cancellation and
supersession of its work. See
[`SwingTerminalMiddleClickPasteRequest`](src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminalMiddleClickPasteRequest.kt)
for completion and lifetime details.

### Selection and cell coordinates

`selectedText()` reads the complete retained selection on the EDT without
clipboard access. It includes offscreen and closed-session content, joins soft
wraps for linear selections, and preserves block-selection row breaks. It reads
current content rather than frozen selection text. `null` means unavailable;
an empty string can represent selected blanks removed by trimming. Clipboard
copy uses the same extraction. Use `createSelectionRange`, `setSelection`, and
`clearSelection` for host-controlled ranges; see the
[selection contract](docs/swing-repaint-optimization.md#2-selection-drag-matrix--text-extraction).

`copyCellPositionAt(x, y, destination)` maps component-local pixels to a
zero-based logical column and displayed-frame row on the EDT. Invalid hits
return `false` and set the caller-owned `Point` to `(-1, -1)`. Use
`copyCellBounds` for the reverse mapping. Both account for the displayed grid,
including padding, bidi placement, and scrolling.

### Cursor presentation

Focused cursors use the application's shape and blink state. Unfocused blocks
become steady outlines; bars and underlines remain steady. Application-hidden
cursors stay hidden.

### Suggestion request ownership

**Experimental.** Suggestions may be incomplete, rank poorly, or behave unexpectedly
in edge cases. Suggestion providers, editing targets, interactions,
and custom views follow the [completion stability policy](../docs/features/completion.md#stability-experimental).

Opt in to `ExperimentalShellSuggestionsApi` where you configure or use suggestions.
Creating, configuring, and binding the terminal without suggestions needs no opt-in.
See the [completion adapter example](../ketraterm-ui-swing-host/README.md#completion-bridge)
for suggestion-specific setup.

Call `setShellSuggestionProvider(provider)` on the EDT before or after binding.
The provider survives rebinding; replacing it cancels current requests without
closing the provider. Passing `null` disables configured provider requests.
The host retains ownership of engines, sources, persistence, and their resources.

The terminal coordinates automatic focus, eligibility, debounce, and session
observation. `smartSuggestionsEnabled` gates all suggestions;
`shellSuggestionsEnabled` gates automatic popups. Set `shellSuggestionAutomaticMode`
to `SwingShellSuggestionAutomaticMode.PARAMETERS_ONLY` to show automatic popups
for subcommands, options, and arguments; the default is `ALWAYS`. An explicit
`requestActiveShellSuggestions()` uses the bound session's command source and
remains available when automatic popups are disabled or limited to parameters.
Automatic refreshes cannot replace an active explicit request.
Popup and inline automation have separate switches and debounce delays. Both
require `smartSuggestionsEnabled`; disabling either automatic switch leaves
explicit requests available. Zero delay starts without debouncing; negative
delays are rejected. Both surfaces default to 75 ms,
with automatic inline disabled. Changes take effect through `reloadSettings()`.
For fully host-owned scheduling, disable both automatic switches and use the
explicit request APIs. Invocation shortcuts remain host-owned.
Late shell-command inventory updates refresh eligible automatic suggestions after
the normal debounce, even when the typed text is unchanged.
Automatic and explicit requests both capture the current editing context before
provider work. Automatic requests that cannot yet capture retry on session
[admission wakeups](../ketraterm-session/docs/session-concurrency-locks.md#conditional-command-edits),
without cancelling an already captured request or requiring another keystroke.
A screen-derived prefix requiring shell confirmation is queried
through the session's installed native hook before collecting suggestions. The
default session target passes its original captured context to
`requestShellCompletions(expected)`; confirmation must preserve that context as
well as match the buffer and cursor. Pending and confirmed native requests retain
their original editing authority across partial redraws and prediction layout
changes; the session validates input, prompt, and geometry revisions independently
of the rendered observation. A stale automatic request is replaced with a
new interaction and handler even when the command text is unchanged. The old
handler is cancelled, never renewed. Custom edit targets own their editing
authority. If the hook cannot confirm the buffer,
suggestions stay unavailable.
Custom providers must honor `SwingShellSuggestionRequest.includeCommandNames`
using their shell-aware command context; the terminal sets it to `false` for
parameter-only automatic requests. The shared completion adapter forwards it
to the engine. Use
`refreshShellSuggestions()` on the EDT when external context such as the working
directory changes. It immediately cancels pending work and closes existing
suggestions, including explicit requests. Automatic suggestions then refresh
after the normal debounce; with automatic popups disabled, request them explicitly again.

The embedded automatic popup updates in place as you type. Previous rows remain
visible while results refresh, but cannot be accepted until fresh results arrive.

Use `SwingSettings.shellSuggestionStyle` to customize the built-in popup:

```kotlin
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingShellSuggestionStyle
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi

@OptIn(ExperimentalShellSuggestionsApi::class)
fun suggestionSettings() = SwingSettings.create {
    it.shellSuggestionStyle = SwingShellSuggestionStyle(
        background = 0xFF202124.toInt(),
        foreground = 0xFFE8EAED.toInt(),
        selectionBackground = 0xFF334455.toInt(),
        selectionForeground = 0xFFFFFFFF.toInt(),
        matchForeground = 0xFF82AFFC.toInt(),
        selectionMatchForeground = 0xFFBBDDFF.toInt(),
    )
}
```

The style also exposes match backgrounds, border, overflow text, and normal/selected
argument-hint colors (`argumentHintForeground` and `selectionArgumentHintForeground`). Every
role has a concrete default in the built-in dark palette. Colors are packed ARGB;
text and fills must be opaque, while a transparent border removes the outline.
Colors are rendered exactly, with no theme lookup or automatic contrast changes.
The built-in popup uses `SwingSettings.font`; icons, spacing, and size bounds scale
with that font.
Call `reloadSettings()` after changing settings; the popup updates without
replacing its suggestions or selection.
Custom views retain control of their own styling. Providers supply label-relative
`matchedRanges`, including disjoint fuzzy matches; the default view colors those
fragments without repeating the match. Providers can supply display-only `argumentHint`
text beside the label; hints never become part of the accepted command.

Nested `PATH` rows show the name first and parent context in `secondaryForeground`
(`selectionForeground` on selection). Long paths shorten to the available width;
hover and accessibility retain the full path, and acceptance inserts the original
replacement. Visible match fragments keep their supplied highlights.
Custom providers set `backslashPathSeparator = false` when `PATH` labels contain
literal POSIX backslashes. The standard completion adapter supplies this policy.

During ordinary typing, old rows remain briefly to avoid flicker. A slow refresh
clears them after 200 ms while collection continues; stale rows cannot be accepted.

For command text outside the session, call `requestShellSuggestions` with its
text, UTF-16 cursor offset, and zero-based cell anchor. Its trigger defaults to
`AUTOMATIC`; pass `EXPLICIT` for an explicit command. The host must replace or hide
the request when that external context changes. Supply an explicit `editTarget`
for a custom editor. Normal terminal completion captures editing authority from
the bound session, which requires a synchronized revisioned command source such
as `TerminalShellCommandLineState` or the optional OSC producer. The default target
uses the producer's prefix-reset capability when available; custom editors keep
their own acceptance contract. A read-only StateFlow model alone cannot admit
conditional edits. See the
[conditional edit contract](../ketraterm-session/docs/session-concurrency-locks.md#conditional-command-edits).

Provider `open` runs on the EDT before background collection; keep it cheap.
Each interaction captures its source and feedback handler and accepts one provider
collection. Lifecycle changes invalidate obsolete results and edits.

| Integration | Host responsibility |
| --- | --- |
| Embedded popup | Configure a provider; optionally replace the keymap or view factory |
| Native or detached popup | Install `SwingShellSuggestionTarget`; use its captured interaction and own popup resources |
| Host-produced results | Capture an interaction with `beginShellSuggestionInteraction` before starting work, then publish on the EDT |

`setShellSuggestionTarget(null)` restores embedded presentation. Use
`copyCellBounds` to anchor a host popup and `setShellSuggestionFailureHandler`
for view-owned diagnostics.

### Inline suffix rendering

The experimental inline API paints caller-supplied ghost text through the same
text renderer as ordinary terminal cells. Capture edit authority before starting
asynchronous work, then present the result on the EDT:

```kotlin
import io.github.ketraterm.session.TerminalCommandEditContext
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi

@OptIn(ExperimentalShellSuggestionsApi::class)
fun showSuffix(view: SwingTerminal, expected: TerminalCommandEditContext, suffix: String): Boolean =
    view.presentInlineShellSuggestion(expected, suffix)
```

The bound session must match the captured context. Presentation requires a
confirmed command-end cursor, focus, no selection or IME composition, and the
live primary viewport with full-screen margins and automatic wrapping. A visible
shell suffix suppresses host ghost text. Preview text is single-line,
control-free, well-formed UTF-16, at most 16,384 code units, and cannot attach to
or modify the command's final grapheme. Invalid or unavailable presentation returns
`false` and clears any previous preview.

Wrapping uses the session's Unicode and ambiguous-width policy. Rendering stops
before an occupied cell or image, preserving whole wide cells and clusters, and
clips at the bottom without scrolling. Directional layouts are suppressed when
they would move existing text or the preview into the command prefix. Font
fallback, shaping, row geometry, and cursor painting use the ordinary renderer.
The optional `foreground` argument supplies ARGB; the default uses the current
terminal foreground at muted opacity.

`clearInlineShellSuggestion()` removes the presentation. Input, invalid edit
authority, focus loss, selection, scrolling, rebind, disposal, or incompatible
geometry remove it too. The view borrows the edit context; clearing presentation
does not cancel it. Ghost text never enters selection copying, search, command
output, scrollback, or the live grid.

The overload above provides rendering only and claims no acceptance keys.
Successful rendering-only presentation also retires pending automatic inline
requests for the current command. For
provider collection and guarded whole-suggestion acceptance, install an independent
inline provider on the EDT. Optional automatic requests reuse the popup scheduler's
eligibility, invalidation, and dismissal logic, with independent timing.

The [runnable consumer example](../ketraterm-examples/README.md) combines normal
local popup completion with an independent delayed inline provider and bounded
command/output context, using public APIs throughout.

```kotlin
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*

@OptIn(ExperimentalShellSuggestionsApi::class)
fun automaticSuggestionSettings() = SwingSettings.create {
    it.smartSuggestionsEnabled = true
    it.shellSuggestionsEnabled = true
    it.shellSuggestionDebounceMillis = 75
    it.inlineShellSuggestionsEnabled = true
    it.inlineShellSuggestionDebounceMillis = 200
}

@OptIn(ExperimentalShellSuggestionsApi::class)
fun configureAutomaticSuggestions(
    view: SwingTerminal,
    popupProvider: SwingShellSuggestionProvider,
    inlineProvider: SwingInlineShellSuggestionProvider,
) {
    view.setShellSuggestionProvider(popupProvider)
    view.setInlineShellSuggestionProvider(inlineProvider)
}
```

Construct the view with these settings, or update its settings provider and call
`reloadSettings()`. Inline providers survive rebinding and remain host-owned.
Changing/removing the configured inline provider or disabling its automatic switch
cancels automatic work without closing a manual interaction. Automatic requests
never replace a current manual interaction. `requestActiveInlineShellSuggestions()`
explicitly requests the configured provider even when automatic inline is disabled.
Automatic inline needs a nonblank prefix; the popup scheduler retains its existing
prefix threshold. Inline work does not refresh merely because popup inventory changes.

For direct control over each request and provider, create an inline interaction
on the EDT. Managed requests respect `smartSuggestionsEnabled`:

```kotlin
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalShellSuggestionsApi::class)
fun requestInline(
    view: SwingTerminal,
    generateSuffix: suspend (SwingInlineShellSuggestionRequest) -> String?,
): SwingShellSuggestionInteraction? {
    val interaction = view.beginInlineShellSuggestionInteraction() ?: return null
    view.requestInlineShellSuggestions(interaction, SwingInlineShellSuggestionProvider { context ->
        SwingShellSuggestionSource(flow {
            val suffix = generateSuffix(context) ?: return@flow
            if (suffix.isNotEmpty()) {
                val offset = context.commandLine.cursorOffset
                emit(listOf(SwingShellSuggestion(suffix, offset, offset, "AI", "COMMAND")))
            }
        })
    })
    return interaction
}
```

Provider `open` captures context on the EDT; its cold flow is collected off the
EDT. Each request owns its cancellation and original edit token. New inline work
supersedes only inline work, and closing the returned interaction cancels it.
Provider completion preserves the final result; cancellation, failure, input,
or lost eligibility retires obsolete work. Feedback comes from the source that
produced the accepted or dismissed publication. Custom publication uses
`interaction.publish(candidates, selectedIndex = 0)`; an unselected item is hidden.
If a valid result arrives before its copied render frame is ready, frame publication
retries presentation using the original edit context and provider result.

Popup and inline providers are independent by default. Their results may differ;
popup updates neither cancel nor restart inline work. Deliberate popup browsing
hides inline presentation, including late arrivals, while retaining its valid
request. `setInlineShellSuggestionCoexistence(EXTEND_POPUP_SELECTION)` explicitly
opts into compatible results: the provider receives `selectedPopupCommandText`
and is reopened when that context changes. Detached popup owners control their
presentation and key routing themselves.

The inline standard keymap accepts the whole visible suggestion with Tab or Right
at the command's end while the popup is passive. Without visible inline text,
Tab reaches the shell until a popup item is deliberately selected. Keyboard navigation
deliberately selects a popup item and hides inline text; Tab or Enter then inserts
that choice without executing it. Enter otherwise reaches the shell. Provider
preselection and hover never enable Tab or Enter acceptance. A refresh preserves an
explicit choice only while an equivalent edit remains available.

After popup acceptance, fresh child results are passive for Tab and Enter, and inline
stays hidden while the accepted command is unchanged. Tab then invokes shell completion;
Enter executes the directory already entered. Navigate to select a child explicitly. Editing,
dismissal, focus loss, and rebinding end that browsing context. External session edits
also retire it, even when the command later returns to identical text. Escape dismisses an
open popup before inline text. With standard bindings, set
`acceptSelectedSuggestionWithEnter = false` to keep Enter shell-owned.

`setShellSuggestionKeymap` and `setInlineShellSuggestionKeymap` accept custom maps
for navigation or acceptance/dismissal, including maps returning null to disable bindings.
`acceptInlineShellSuggestion()` and `dismissInlineShellSuggestion()` provide the
same guarded operations for host actions. A failed acceptance never falls through
to accepting the other surface or sending that key to the shell. Each mapped gesture
stays bound to its original publication; reentrant keymaps cannot redirect it to a new result.

For long suggestions, hosts can opt into `acceptInlineShellSuggestionNextWord()` or
map `SwingShellSuggestionAction.ACCEPT_NEXT_WORD` in the inline keymap. It is
**unbound by default**. For example, to add Ctrl+Right while retaining the defaults:

```kotlin
view.setInlineShellSuggestionKeymap { event ->
    if (event.keyCode == java.awt.event.KeyEvent.VK_RIGHT &&
        event.modifiersEx == java.awt.event.InputEvent.CTRL_DOWN_MASK
    ) {
        SwingShellSuggestionAction.ACCEPT_NEXT_WORD
    } else {
        SwingShellSuggestionKeymap.INLINE_STANDARD.actionFor(event)
    }
}
```

The popup ignores `ACCEPT_NEXT_WORD`, so a shared custom keymap can expose it on
both surfaces without intercepting inline acceptance.

Each step includes leading whitespace and the next non-whitespace run. This is a
text operation with the same rules in every shell; it does not parse shell syntax.
Paths stay together and spaces split quoted arguments too. Examples:

| Current command | Suggestion | After one step |
| --- | --- | --- |
| `git s` | `git status --short` | `git status` |
| `git status` | `git status  --short` | `git status  --short` |
| `cd ` | `cd src/main/ test/` | `cd src/main/` |
| `echo ` | `echo "hello world"` | `echo "hello` |

Grapheme clusters remain intact. Acceptance never executes input. The remainder
is hidden until the shell reports the accepted text and end cursor, with the writer
idle. Intervening input, resize, shell metadata changes, clearing, or a newer request
discard it. A shell that never acknowledges the prefix cannot restore the remainder.
The saved candidate and its source feedback are retained; no provider rerun is needed.
`PARTIALLY_ACCEPTED` feedback describes only the admitted prefix; the completion-learning
adapter ignores it. Finishing the candidate emits ordinary `ACCEPTED` feedback.

Managed rendering accepts only append-only outcomes. Clipping limits displayed
cells; whole-suggestion acceptance inserts the entire suggestion. Clearing managed work cancels
its owned edit context; a token passed to the rendering-only overload remains
caller-owned. The standalone app's opt-in completion source is documented in the
[completion catalog](../docs/features/completion.md#inline-suffix-presentation).

## How to Extend: Custom Host Services

`SwingHostServices` is an immutable snapshot of host-owned services, supplied at
construction. `create`, `copy`, or `builder` configure selected services without
starting or closing them. Use the public adapters rather than assembling internal
painters or input controllers.

| Service | Purpose |
| --- | --- |
| `clipboardHandler` | Synchronous text copy/read and optional primary-selection access |
| `middleClickPasteHandler` | Host-owned local paste gesture, including deferred clipboard access |
| `hyperlinkHandler` / `hyperlinkDetector` | OSC 8 navigation / discovery of links in retained text |
| `fontResolver` | Host font fallback policy |
| `hostKeyHandler` / `contextMenuHandler` | Host keyboard actions / local right-click menus |
| `viewportListener` / `scrollbarOverlayEnabled` | External scrollbar integration or the built-in overlay |
| `shellSuggestionKeymap` / `shellSuggestionViewFactory` | Suggestion action mapping and embedded presentation |
| `uiDispatcher` | UI scheduling; component work must still run on the EDT |

Numeric-keypad events carry Num Lock state when AWT can report it, preserving
numeric typing in application keypad mode. Unsupported or headless toolkits
retain existing keypad behavior without inferring lock state or repeatedly
querying an unavailable facility.

Smooth scrolling is enabled by default. Library hosts can disable animation:

```kotlin
val settings = SwingSettings.create { it.smoothScrolling = false }
```

Wheel, paging, and row scrolling then apply their destinations immediately while
precise wheel input still accumulates into whole rows. Supply an updated settings
snapshot and call `reloadSettings()` to change this at runtime; disabling animation
settles any pending scroll at its destination. Direct scrollbar dragging and
`scrollToScrollbackOffset` always apply immediately.

Admitted typing, editing keys, and paste immediately return to the live viewport
and cancel pending scroll animation. Local shortcuts, modifier and lock keys,
focus changes, and key releases preserve the scrollback position. New output
keeps scrolled content anchored by default; set `SwingSettings.Builder.scrollOnOutput`
to `true` to return to live output when new rows enter history. Input rejected by
session startup, closure, or capacity limits does not move the viewport.

`mouseReportingEnabled` controls application mouse reporting.
`alternateScreenWheelToArrowEnabled` independently controls wheel-to-arrow input
in the alternate screen. Both default to `true`; mode 1007 must also be enabled
for wheel-to-arrow input. Shift restores local interaction unless the
application requests Shift capture. A started button gesture keeps its route
until release.

`columnSpacing` adds signed logical pixels to each cell advance. Negative values
condense the grid without scaling the font. Construction and reload reject
resolved cell widths below one pixel or overflowing geometry; a rejected reload
keeps the previous settings.

<a id="hyperlink-detector-contract-and-migration"></a>

## Hyperlink detection

`SwingHyperlinkDetector.detect` is suspending and returns completed, immutable
results. Detectors run outside painting and Swing event handling. Choose
independent-line or ordered-content context according to the detector's text
dependencies. Actions and hover callbacks run on the EDT. OSC 8 links use
`hyperlinkHandler`; detected links supply their own actions.

The [hyperlink integration contract](docs/hyperlink-detection.md) describes
coordinates, cancellation, ordered replay, configuration changes, and activation.
See [Module.md](Module.md) for rendering architecture and maintainer guidance.

## Sub-Documentation

- [Text rendering and font fallback](docs/bifurcated-text-rendering.md)
- [Repainting, viewport geometry, and selection](docs/swing-repaint-optimization.md)
- [Hyperlink detection](docs/hyperlink-detection.md)
- [Configuration and construction](../docs/library/configuration.md)
- [Feature map](../docs/terminal-feature-map.md) and [gap map](../docs/terminal-feature-gap-map.md)

<a id="consumer-and-abi-verification"></a>

See [Module.md](Module.md#consumer-and-abi-verification) for source layout and verification.
