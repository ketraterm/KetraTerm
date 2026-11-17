# Host integration example

This runnable host uses published library artifacts. It owns a real shell process and a standard Swing window.
It does not use the workspace, application, benchmark or testkit modules.

## Run

Use JDK 25. Replace `{version}` with a published KetraTerm version.
From this directory, run:

```powershell
..\..\gradlew.bat -p . '-PlibraryVersion={version}' run
..\..\gradlew.bat -p . '-PlibraryVersion={version}' run --args="pwsh -NoLogo -NoProfile"
..\..\gradlew.bat -p . '-PlibraryVersion={version}' check
..\..\gradlew.bat -p . '-PlibraryVersion={version}' desktopTest
```

On Linux or macOS, use `../../gradlew`. For example, pass `--args='/bin/bash --noprofile --norc -i'`.
The default command is Windows PowerShell on Windows. Other systems use the default PTY shell.
The desktop test requires an active desktop, a working PTY and PowerShell or `/bin/bash`.

The standalone build resolves the BOM and these Maven artifacts:

- `ketraterm-swing`: the terminal pipeline and reusable Swing view.
- `ketraterm-pty`: the local process connector.
- `ketraterm-shell-launch`: shell hook preparation and launch profiles.
- `ketraterm-ui-swing-host`: search chrome, shortcut defaults and completion presentation adaptation.
- `ketraterm-completion-host`: local filesystem completion.
- `ketraterm-completion-session`: native shell completion adaptation without Swing dependencies.

The build verifies that its runtime contains no product modules or substituted projects.
Snapshots use the Sonatype snapshot repository. Coroutines Swing supplies the EDT dispatcher.

For library development, run these tasks from the repository root:

```powershell
.\gradlew.bat :ketraterm-examples:runHostIntegration
.\gradlew.bat :ketraterm-examples:test --tests "io.github.ketraterm.examples.host.HostIntegrationExampleTest"
.\gradlew.bat :ketraterm-examples:desktopTest --tests "io.github.ketraterm.examples.host.HostIntegrationDesktopTest"
.\gradlew.bat verifyHostIntegrationExample
```

The last task stages Maven artifacts and checks this standalone build against them. It does not publish to a remote repository.

## Composition and ownership

[`HostIntegrationExample.kt`](src/main/kotlin/io/github/ketraterm/examples/host/HostIntegrationExample.kt) uses these public entry points:

```kotlin
import io.github.ketraterm.shell.launch.TerminalShellLaunch
import io.github.ketraterm.pty.PtyConnectors
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.workspace.TerminalProfile
```

`TerminalProfile` has a historical package name. The shell-launch artifact owns it; no workspace artifact is needed.

The launch prepares the shell arguments, environment and metadata producer. The host creates the connector and session on `Dispatchers.IO`.
It attaches the launch while the session is `Created`. It constructs and binds the view on the EDT.
It awaits binding outside the EDT before starting output. The PTY input policy preserves local Enter and paste semantics.

The window owns the view lifetime. The outer scope owns the session and launch lifetime.
Closing the window disposes the view on the EDT, then closes the session and launch off the EDT.
A close request or host cancellation during startup requests session shutdown before waiting for the startup worker.
A partial startup follows the same ownership rules. The view never closes a supplied session.

[`HostTerminalPane.kt`](src/main/kotlin/io/github/ketraterm/examples/host/HostTerminalPane.kt) supplies the host chrome and services:

- Toolbar and platform shortcuts call the same copy, paste, search and suggestion actions.
- The supplied clipboard handler controls host clipboard access.
- Immutable settings update the theme and font through `reloadSettings` and its completion callback.
- The bounded output action reads at most 8,192 UTF-16 units and 128 physical rows off the EDT.
- The output status distinguishes unavailable output from incomplete output. Rendered output is not a process transcript.
- Closing the pane cancels its asynchronous actions and disposes its view resources.

[`HostSuggestionList.kt`](src/main/kotlin/io/github/ketraterm/examples/host/HostSuggestionList.kt) supplies a stock `JList` through `SwingShellSuggestionViewFactory`.
It renders the bounded snapshot and forwards pointer events with that snapshot. KetraTerm owns keyboard routing, selection, placement and guarded edits.
The host does not implement another completion controller. Suggestions remain experimental, as documented by their API annotations.

## Use an existing host shell model

The real launcher installs KetraTerm's shell hooks. A host with an existing shell model uses `TerminalShellIntegrationFactory.host` instead.
It supplies `TerminalShellIntegrationState` and `TerminalShellCommandLineState` when creating the session.
It then uses the same pane and completion bridge. It does not need KetraTerm's OSC producer.

The host must supply real lifecycle events and complete editing state from its own source.
It publishes metadata in output order. It captures a line identity and publishes its marker under `context.withTerminalState`.
It releases borrowed frame callbacks before publishing metadata. It publishes `null` while the editing state is not trustworthy.
Neither queue idleness nor a local write acknowledges shell processing.

[`HostIntegrationExampleTest.kt`](src/test/kotlin/io/github/ketraterm/examples/host/HostIntegrationExampleTest.kt) demonstrates this path with a deterministic connector.
Its direct metadata fixtures represent an external host model. They are not another shell protocol implementation.
The tests cover output extraction, clipboard, search, live settings, accepted and stale suggestion edits, and disposal during a clipboard read.

[`HostIntegrationDesktopTest.kt`](src/test/kotlin/io/github/ketraterm/examples/host/HostIntegrationDesktopTest.kt) runs the real launch path twice.
It checks shell output, completed-command extraction, resize, settings reload, view disposal and process closure.

## Host-specific work

The example uses local filesystem completion. A remote host supplies a matching remote source instead.
The host supplies its own settings persistence, keymap and localized labels. This example keeps its labels in the source for readability.
The library provides terminal content and semantic contracts. It does not require a host document model, copied internals or reflection.
