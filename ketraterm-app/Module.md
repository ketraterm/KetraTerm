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

## Native suggestion smoke checks

`StandaloneSuggestionIntegrationTest` combines the standalone completion registry,
default Swing popup and keyboard handlers with live PTY sessions. It opens short-lived
test windows and uses temporary settings and learning paths. It is part of the
regular test suite and skips native cases unless explicitly enabled. Run it on an
unlocked desktop with the installed shells:

```powershell
./gradlew.bat :ketraterm-app:test --tests '*StandaloneSuggestionIntegrationTest' '-Dterminal.pty.host=true' '-Dterminal.pty.wsl.distribution=Ubuntu'
```

The tests report editing/cancellation, live aliases, and directory context as
independent scenarios, each with a fresh session. `StandaloneSuggestionShell`
owns the launch matrix and prerequisite checks. `StandaloneSuggestionFixture`
owns session/window lifetime, component input, event waits, and popup recording;
behavior assertions remain in `StandaloneSuggestionIntegrationTest`.

The matrix keeps Windows PowerShell and PowerShell 7 as separate cases. It also
covers direct Bash, Git Bash, zsh, fish, and explicit Bash/zsh/fish launches through
both WSL and the Ubuntu launcher. Direct profiles use the workspace's shell
discovery; PowerShell on Unix also supports `pwsh` on `PATH`.

The WSL property is optional and names the distribution to test. Missing profiles,
missing guest shells, an omitted WSL distribution, and a missing graphical display
are reported as skips. A configured distribution that cannot start fails the test.
On Linux/macOS, use `./gradlew` and omit the WSL property; Linux CI can supply a
virtual display with `xvfb-run -a ./gradlew ...`.

Every available case checks automatic publication, deletion, acceptance,
cancellation of a deliberately stalled request, and command execution. Live-command
cases also discover and accept a session-defined alias. Fish has no command inventory;
Bash/zsh inventory checks additionally require the prerequisites documented in
[shell support](../docs/features/shells.md#prompt-hooks-and-completion-dialects).
Missing inventory prerequisites leave the common interaction checks enabled.
Direct shells check local directory completion followed immediately by Enter and
completion relative to the new working directory.
Popup renders are written under `build/reports/suggestion-smoke` in this module.
Guest directory completion is checked as unavailable, consistent with the
[local filesystem boundary](../docs/features/completion.md#sources-by-host).
These checks exercise component keyboard events; they do not automate the desktop
or validate OS keyboard delivery and window-manager placement.
Shells without automatic prompt hooks (such as cmd, Nushell, and sh) are outside this
popup matrix; launch recognition alone does not supply an editable completion context.
