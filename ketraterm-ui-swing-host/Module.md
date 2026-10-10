# Module ketraterm-ui-swing-host

Optional host-facing Swing helpers in `io.github.ketraterm.ui.swing.host`.
The source module's `README.md` describes consumer wiring and lifecycle.

### Ownership and dependencies

The module depends on the reusable Swing terminal, the completion API, and
coroutines. It adapts their public contracts without owning a terminal session,
transport, completion engine, source selection, or persisted learning.
Standalone and IntelliJ products configure these helpers independently.

| Concern | Host helper | Authoritative behavior |
| --- | --- | --- |
| Search chrome | `SwingTerminalSearchBar`, `SwingTerminalOverlayPane`, search palette | Search scanning and highlights remain in `SwingTerminal`. |
| Host actions | Shortcut map and context-menu builder | Hosts install bindings and choose product actions. |
| Completion | Suggestion provider and feedback recorder | Completion evaluates candidates; Swing coordinates request lifetime and editing admission. |
| Clipboard consent | Pane prompt, window reader, dialog and message helpers | Session controls permission, deadlines, and terminal replies; hosts provide clipboard access and presentation. |

### Threading and lifetimes

Construct and manipulate Swing components on the EDT. Completion `open` captures
request context and feedback there; flow collection stays in the owning caller's
coroutine context. Feedback sinks are synchronous, so hosts own serialization
and any dispatch needed for learning updates.

Clipboard prompts and presenters belong to the EDT; native reads use the reader's
I/O dispatcher. Closing a pane cancels its prompt, while a native read already
in progress retains the global admission slot until the call returns. Search
observations stop when the bar closes or becomes undisplayable. Helpers do not
close the terminal, session, or completion engine.

### Validation

Tests cover search state and palette changes, overlay geometry, shortcut
validation, completion context/feedback preservation, localization, clipboard
admission, prompt cancellation, and session read/write ordering. See the [repository architecture](../ARCHITECTURE.md) for cross-module relationships.
