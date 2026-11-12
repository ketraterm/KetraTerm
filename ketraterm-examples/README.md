# Runnable library examples

This unpublished module embeds KetraTerm through public library APIs. It has no
dependency on the standalone app and is built and tested with the repository.
Use JDK 25 and a graphical desktop, with PowerShell, Bash, or zsh installed.

## Independent popup and inline providers

From the repository root:

```shell
./gradlew :ketraterm-examples:run
```

On Windows:

```powershell
.\gradlew.bat :ketraterm-examples:run
```

The workspace discovers a local shell and installs its supported prompt hooks.
Pass an executable and arguments explicitly to choose another shell:

```shell
./gradlew :ketraterm-examples:run --args="/bin/bash -l"
```

```powershell
.\gradlew.bat :ketraterm-examples:run --args="pwsh -NoLogo"
```

The popup uses command specifications, local path completion, and the session's
shell completion provider. Inline uses a separate, deterministic provider with
a 750 ms simulated service delay. It sends no network requests, needs no API key,
and saves no history. Type `echo ext` or `git st` to see it. Completing a command
and typing its prefix also lets the simulation reuse matching directory history.
The popup and inline results may differ deliberately.

| Control | Behavior |
| --- | --- |
| Automatic popup / Automatic inline | Independently enable requests while typing; both default on in this example. |
| Ctrl+Space | Request the popup manually, including when automatic popup is off. |
| Ctrl+Shift+Space | Request inline manually, including when automatic inline is off. |
| Up / Down | Deliberately select a popup item and hide inline text. |
| Tab / Right | Accept visible inline text while the popup is passive. |
| Ctrl+Right | Accept the next word of visible inline text while the popup is passive. |
| Tab / Enter | Insert a deliberately selected popup item. |
| Enter without a deliberate popup selection | Execute the current command, preserving the shell's own handling. |
| Escape | Dismiss the popup first, then inline. |

Without visible inline text or a deliberate popup selection, Tab reaches the
shell. Requests require an editable prompt. Native shell predictions can prevent
host ghost text from appearing; the example leaves shell prediction settings to
the user. See [shell availability](../docs/features/completion.md#availability).

Try typing more before the simulated reply arrives, or selecting a popup item
during that delay. A late reply cannot edit newer input or take over popup
navigation. Close the window to cancel view-owned requests and asynchronously
close the workspace and its process.

To try next-word acceptance, type `echo ext` and wait for the ghost text. Press
Ctrl+Right to reach `echo external`, then again for `echo external inline`, then
`echo external inline suggestion`. Each step waits for the shell's echo before
showing the remainder. Enter executes only the text already accepted; Tab or
Right accepts the entire remaining suggestion. With no visible inline text,
Ctrl+Right reaches the shell for its usual word navigation.

This example opts into the binding using `setInlineShellSuggestionKeymap` and
`SwingShellSuggestionAction.ACCEPT_NEXT_WORD` in
[`SuggestionExampleWindow`](src/main/kotlin/io/github/ketraterm/examples/suggestions/SuggestionExampleWindow.kt).
Other inline keys fall back to `SwingShellSuggestionKeymap.INLINE_STANDARD`.
See the [inline contract](../ketraterm-ui-swing/README.md#inline-suffix-rendering)
for word boundaries, Unicode handling, and echo validation.

## Replacing the local simulation

The integration is in
[`ExternalInlineSuggestions`](src/main/kotlin/io/github/ketraterm/examples/suggestions/ExternalInlineSuggestions.kt).
Replace `::simulateCompletion` at its construction with a suspending function
of type `suspend (SuggestionContext) -> String?`. Return a complete proposed
command or null. Keep the model independent of popup candidates: the terminal
decides whether the result safely extends the current command.

`open` only captures request-owned state. Its cold flow reads context on an I/O
dispatcher, then calls the suspending provider. It launches no background scope.
The terminal owns collection and cancellation, the original editing authority,
rendering, key routing, and conditional acceptance. Operational provider failures
reach the view's standard diagnostics; cancellation propagates. A real network
client remains host-owned and must support request cancellation and be closed
with the host lifetime.

[`SuggestionContext`](src/main/kotlin/io/github/ketraterm/examples/suggestions/SuggestionContext.kt)
contains the original command and UTF-16 cursor offset, the host's shell profile
kind, the reported working directory URI, and at most five completed command
metadata/output pairs, newest
first. Extraction visits at most 32 records and copies at most 2,048 UTF-16 units
and 32 physical output rows per completed command. Changed metadata discards the
snapshot. Metadata includes exit codes, directories, and start/finish timestamps.
Unavailable output stays null; `truncated` and `historyIncomplete` are preserved.
Output is rendered terminal text, without separate stdout/stderr streams. See the
[command-output contract](../ketraterm-session/README.md#completed-command-output).

This sample keeps context local. Commands and output may contain credentials or
other private data, and terminal output is untrusted input. Decide what the host
may send before connecting a service; do not treat terminal output as instructions
or execute generated commands automatically. The adapter rejects oversized and
control-bearing responses before publishing.

The source has three responsibilities:

- `SuggestionExampleWindow` owns shell/view wiring, independent automatic
  switches, typed invocation shortcuts, and asynchronous shutdown.
- `readSuggestionContext` makes a bounded detached snapshot away from the EDT.
- `ExternalInlineSuggestions` adapts a suspending external provider to the
  existing managed inline API.

See the [Swing provider contract](../ketraterm-ui-swing/README.md#inline-suffix-rendering)
and [completion bridge](../ketraterm-ui-swing-host/README.md#completion-bridge)
for the reusable APIs. Published dependency setup is in the
[library guide](../README.md#using-the-libraries); the example's project
dependencies name the corresponding published artifacts.

## Verification

```shell
./gradlew :ketraterm-examples:test
./gradlew :ketraterm-examples:desktopTest
```

The deterministic tests cover real OSC metadata/output extraction, bounds and
incomplete history, unavailable output, detached context, simulated timing,
cancellation after a service has started, malformed replies, and propagated
provider failures. Shared Swing interaction tests own the terminal's stale-edit,
popup navigation, and key-routing contracts. The desktop smoke test requires an
installed supported shell and display; it starts the real example, checks the
controls, and waits for asynchronous workspace cleanup before window disposal.
