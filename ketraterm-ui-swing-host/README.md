# KetraTerm Swing Host

Optional host-side helpers for applications embedding `SwingTerminal`: search
chrome, terminal action bindings, context menus, completion adapters, and
clipboard consent. Hosts explicitly construct and install the helpers they need.
The terminal view and session retain their own rendering and input responsibilities.

## Dependency

Add `io.github.ketraterm:ketraterm-ui-swing-host` alongside your terminal entry
point, using the [BOM and repository setup](../README.md#using-the-libraries). This module
exposes the Swing UI, completion API, and coroutine dependencies. Filesystem
completion and persistence are separate optional modules.

## Search chrome

`SwingTerminalSearchBar` delegates scanning, highlights, and result navigation
to the terminal's search API. `SwingTerminalOverlayPane` places the bar over the
terminal content, so opening it does not reduce the grid's available space.

Construct Swing components on the event dispatch thread (EDT). This example
wraps an existing terminal; its owner still binds and disposes the terminal.

```kotlin
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import javax.swing.JComponent
import javax.swing.SwingUtilities

class TerminalSearchPane(terminal: SwingTerminal) : AutoCloseable {
    init {
        check(SwingUtilities.isEventDispatchThread())
    }

    private val searchBar = SwingTerminalSearchBar(terminal)
    val component: JComponent =
        SwingTerminalOverlayPane(terminal, searchBar.component)

    fun openSearch() = searchBar.open()

    override fun close() = searchBar.close()
}
```

Mount `component` in the host window and invoke `openSearch` from a host action.
Call `close` when removing the pane; it cancels search observation and clears
active highlights. Removing the bar from a displayable hierarchy also cancels
its observation. `open`, `close`, and `refreshColors` enqueue work when called
off the EDT; read `isOpen` and manipulate `component` on the EDT.

Use `SwingTerminalSearchColors.create`, `copy`, or a builder to supply an immutable
palette through `refreshColors(colors)`. Updating colors preserves the query and
visibility. The palette is retained across close/open cycles.

## Host actions and context menus

`SwingTerminalHostShortcutMap.platformDefault()` supplies optional platform
bindings. It does not register Swing actions. Install the entries with
`forEachShortcut`, or resolve a key event with `actionFor(keyCode, modifiersEx)`
inside your `SwingHostServices` key handler. Return whether the host consumed the
event so unhandled keys continue to terminal input.

Use `withShortcut` and `withoutShortcut` to customize bindings. Shortcuts must
remain unique and use extended keyboard modifier masks.

`SwingTerminalContextMenuItems.addTerminalActions` appends selection, paste,
search, screen-clear, and applicable hyperlink actions to a host-owned
`JPopupMenu`. Pass the terminal's context-menu request and your search-opening
callback, append product-specific items, and show the menu from the host callback
on the EDT. See [Swing host services](../ketraterm-ui-swing/README.md#how-to-extend-custom-host-services)
for callback configuration.

## Completion bridge

`SwingCompletionSuggestionProvider` adapts a
[`TerminalCompletionEngine`](../ketraterm-completion/README.md) to the terminal's
suggestion contract. It preserves replacement ranges, source identity,
presentation fields, matched ranges, and opaque feedback tokens. The host selects
the engine and sources and supplies current profile, directory, and shell metadata.

```kotlin
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import javax.swing.SwingUtilities

fun installCompletion(
    terminal: SwingTerminal,
    engine: TerminalCompletionEngine,
    contextProvider: () -> SwingCompletionContext,
    feedbackHandler: SwingShellSuggestionFeedbackHandler =
        SwingShellSuggestionFeedbackHandler.NONE,
) {
    check(SwingUtilities.isEventDispatchThread())
    terminal.setShellSuggestionProvider(
        SwingCompletionSuggestionProvider(
            engine = engine,
            contextProvider = contextProvider,
            sourceLabels = mapOf("spec" to "Commands", "path" to "Files"),
            feedbackHandler = feedbackHandler,
        ),
    )
}
```

The terminal opens the provider on the EDT, capturing one immutable context and
feedback handler before background collection. Keep `contextProvider` cheap.
Direct calls to `suggestions(request)` capture context in the caller's thread;
the returned flow is cold and runs engine work only when collected. This adapter
does not choose a dispatcher, launch jobs, or close the engine. Collection
cancellation and engine failures propagate to the caller.
An invalid converted completion request produces one empty suggestion snapshot.

Optional `sourceLabels` customize display labels, including localization.

`SwingCompletionFeedbackRecorder.createHandler()` maps accepted and explicitly
dismissed suggestions to a host-owned learning sink. Admission rejection is
ignored. The host owns learning-store synchronization.

Configure completion on the EDT and keep engine resources alive for their owning
host's lifetime. See [suggestion request ownership](../ketraterm-ui-swing/README.md#suggestion-request-ownership)
for editing authority, request invalidation, custom presentation, and diagnostics.
Use [completion-host](../ketraterm-completion-host/README.md) for filesystem sources
and [completion-persistence](../ketraterm-completion-persistence/README.md) for
optional saved learning.

## Clipboard consent and dialogs

`SwingClipboardReadPrompt` owns cancellable consent for one terminal pane.
Construct it with a product dialog callback, or bridge
`SwingMessageDialogs.showModeless`. Operations and decisions belong to the EDT;
close the prompt there when disposing the pane. Denial, Escape, cancellation,
and closure cannot approve a read. A block decision lasts for the pane's lifetime.

Use one `SwingClipboardReader` per window. Call its suspending `read` on the EDT
with the requesting pane's prompt, message, and clipboard handler, after earlier
posted clipboard writes. It enforces admitted permission and reads native
clipboard data on its I/O dispatcher. Busy prompts are denied; a busy native
read is unavailable. Only one native read runs across reader instances, and a
cancelled blocking call keeps that slot until it returns. The session owns the
request deadline, permission revocation, and response serialization.

`SwingClipboardPrompts` builds consent text from terminal names and character
counts without placing clipboard contents in the dialog. `SwingDialogRequest`
holds plain text and copied choices; `SwingMessageDialogs` escapes message text
for Swing display. Modal and modeless presenters return an option index or
`null` for dismissal. Hosts still wire these helpers into their session's
clipboard services and choose write-consent policy.

## Localization and host settings

Supply `SwingHostMessages` to search, context-menu, dialog, and clipboard helpers
to integrate a host message framework. `forLocale(locale, bundle)` uses JDK
resource bundles and `MessageFormat`, with missing keys falling back to the
bundled English catalog. The selected locale is captured; static component
labels are resolved at construction. The [catalog](src/main/resources/io/github/ketraterm/ui/swing/host/SwingHostMessages.properties)
defines keys and arguments. Message arguments are plain text, not markup.

`SwingTerminalSettingsBounds` provides shared preference-control ranges for
hosts. These limits do not define the core grid or renderer's valid configuration.

For packages and dependencies, see [Module.md](Module.md).
