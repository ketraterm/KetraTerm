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
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import java.awt.Font
import javax.swing.SwingUtilities

fun createTerminalView(session: TerminalSession): SwingTerminal {
    return createUnboundTerminalView().apply { bind(session) }
}

fun createUnboundTerminalView(
    provider: SwingShellSuggestionProvider? = null,
): SwingTerminal {
    check(SwingUtilities.isEventDispatchThread()) { "Create terminal views on the EDT" }

    val settings = SwingSettings.create {
        it.palette = TerminalTheme.ONE_DARK.createPalette()
        it.font = Font("Cascadia Mono", Font.PLAIN, 15)
        it.columns = 80
        it.rows = 24
    }

    return SwingTerminal(settingsProvider = { settings }).apply {
        setShellSuggestionProvider(provider)
    }
}
```

The font name is a host choice; use a font available in your environment.
`columns` and `rows` set the preferred size. Once laid out, the visible cell
dimensions determine the live session's grid and connector size.

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
threading contract. `bind`, `unbind`, `dispose`, and `reloadSettings` execute
immediately on the EDT and dispatch asynchronously when called elsewhere. An
off-EDT call does not mean that the operation has completed.

Use one controlling view per session. Settings, dimensions, and the published
viewport belong to the session; multiple components cannot scroll or configure
it independently. After session closure, the view retains the final grid and
selection. Width changes clip rather than reflow it; height changes expose a
bottom-anchored window over retained rows. Presentation changes do not restart
the session.

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
Application mouse reporting takes priority unless Shift forces local interaction.
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

Call `setShellSuggestionProvider(provider)` on the EDT before or after binding.
The provider survives rebinding; replacing it cancels current requests without
closing the provider. Passing `null` disables configured provider requests.
The host retains ownership of engines, sources, persistence, and their resources.

The terminal coordinates automatic focus, eligibility, debounce, and session
observation. `smartSuggestionsEnabled` gates all suggestions;
`shellSuggestionsEnabled` gates automatic popups. An explicit
`requestActiveShellSuggestions()` uses the bound session's command source and
remains available when automatic popups are disabled. Use
`refreshShellSuggestions()` to reconcile changes to external ranking context.

For command text outside the session, call `requestShellSuggestions` with its
text, UTF-16 cursor offset, and zero-based cell anchor. Its trigger defaults to
`AUTOMATIC`; pass `EXPLICIT` for an explicit command. The host must replace or hide
the request when that external context changes. Supply an explicit `editTarget`
for a custom editor. Normal terminal completion captures editing authority from
the bound session, which requires a synchronized revisioned command source such
as `TerminalShellCommandLineState` or the optional OSC producer. A read-only
StateFlow model alone cannot admit conditional edits. See the
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

`mouseReportingEnabled` controls application mouse reporting.
`alternateScreenWheelToArrowEnabled` independently controls wheel-to-arrow input
in the alternate screen. Both default to `true`; Shift forces local mouse
interaction.

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
- [Configuration and construction](../docs/library-configuration.md)
- [Feature map](../docs/terminal-feature-map.md) and [gap map](../docs/terminal-feature-gap-map.md)

<a id="consumer-and-abi-verification"></a>

See [Module.md](Module.md#consumer-and-abi-verification) for source layout and verification.
