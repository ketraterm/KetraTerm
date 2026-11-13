# Module ketraterm-app

Standalone Swing application and product assembly. See [README.md](README.md)
for launching and configuring it.

## Dependencies

| Dependency | Role |
| --- | --- |
| `ketraterm-ui-swing`, `ketraterm-ui-swing-host` | Terminal view and shared host controls. |
| `ketraterm-workspace` | Profiles, tabs, and local session ownership. |
| `ketraterm-completion`, `ketraterm-completion-host`, `ketraterm-completion-persistence` | Evaluation, local sources, and saved learning. |
| FlatLaf and FlatLaf extras | Window theme and desktop UI support. |
| Kotlin coroutines core and Swing | Background work and EDT dispatch. |

These are implementation dependencies. The runtime uses the SLF4J no-op binding.

## Components

`WindowFactory` constructs window chrome. `TabManager` coordinates tabs, splits,
host permissions, and shutdown through `TerminalWorkspace`. Each `TerminalPane`
owns its terminal view, search bar, prompts, and shortcut registrations. Closing
a pane releases those UI resources; closing its workspace tab closes the session.

The pane installs one completion provider through the Swing view's
`setSharedShellSuggestionProvider`. The view owns one debounce and source
collection, presents the best result inline first, and publishes the remaining
popup rows in the same EDT update. Failed inline presentation leaves the full
ranking in the popup. Browsing retains the omission to keep navigation stable.
The view owns cancellation, source feedback, rendering, and edit admission.
`SuggestionKeyBindings` holds typed acceptance and invocation shortcuts.
`ShortcutEditor` records pressed keys on the Suggestions settings page;
`KetraTermConfigManager` parses and serializes their TOML representation.
Library hosts can supply their own keymap implementations.

`TerminalPasteClipboard` reviews unsafe explicit paste before returning captured
text to the view. `TerminalBellDispatcher` bounds and coalesces per-window feedback
before the EDT applies current preferences and pane-lifetime checks.
`TerminalExitBanner` presents unsuccessful or unknown session termination beside
the retained terminal view; process cleanup remains session-owned.

`KetraTermConfigManager` loads and replaces settings files.
`KetraTermSettings` publishes saved immutable preferences to EDT consumers.
`StandaloneCompletionRegistry` composes the completion modules for a window.
Its local path and Git reference/remote sources use shared source-directory resolution.
`StandaloneGitCompletionLoader` owns bounded Git process queries and request-linked
cancellation, returning a `TerminalGitCompletionSnapshot`. The shared Git source
owns mode selection, reference eligibility, matching, and refspec edits.

## Configuration

The [configuration reference](docs/profile-config-toml.md) describes the persisted
format. Settings are saved before publication to open panes; process launch
options apply to new sessions.

## Desktop suggestion scenarios

`StandaloneSuggestionDesktopTest` covers editing/cancellation, live aliases, and
directory context as independent real-shell scenarios. `StandaloneSuggestionShell`
owns launch variants and prerequisite checks. `StandaloneSuggestionFixture` owns
window/session lifetime, component input, event waits, and popup recording.
Behavior assertions remain in the tests.

Run `./gradlew :ketraterm-app:desktopTest` for all application window tests, or add
`--tests '*StandaloneSuggestionDesktopTest'` for these scenarios alone. See the
[shared test guide](../docs/development/testing.md) for environment tags, CI,
required shell coverage, reports, and the WSL command.

Direct shells check directory-only filtering, acceptance followed immediately by
Enter, and completion in the resulting working directory. Guest filesystem sources
are checked as unavailable under the documented [filesystem boundary](../docs/features/completion.md#sources-by-host).
Popup renders are written to `build/test-artifacts/desktopTest/suggestions`. Component-driven
input does not verify OS keyboard injection or packaged application installation.
