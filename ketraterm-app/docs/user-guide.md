# Using KetraTerm

KetraTerm runs your local shells in tabs and split panes. Platform packages
include a Java runtime; the Java-preinstalled archive requires Java 25.

## Shells and sessions

Choose an installed shell from the profile menu when opening a terminal.
Windows profiles include Windows PowerShell, PowerShell, Git Bash, WSL,
Ubuntu, and Command Prompt. On macOS and Linux, KetraTerm discovers zsh,
Bash, fish, Nushell, and sh. Profiles depend on the executables available on
your machine; WSL discovery uses the default distribution.

Use tabs for separate sessions and split panes to keep sessions side by side.
Tabs can have custom names and accent colors. The app does not restore sessions
after a restart.

When a process exits unsuccessfully, the terminal retains its output and shows
an exit-status banner with a Close button. Transport failures and exits without
a reported status are retained too. You can still scroll, select, copy, and
search; the ended session accepts no input. Closing it does not ask to kill a
process. Successful exits keep the normal automatic close behavior.

## Commands and output

Interactive PowerShell, Bash, zsh, and fish launches receive prompt hooks.
These enable command markers, exit status, navigation between retained commands,
and copying or exporting command output. WSL needs an explicitly selected
supported shell for automatic hooks. Command Prompt and Nushell do not receive
these hooks. Prompt customizations can affect command tracking.

Use terminal search to find text in the visible screen and retained history.
Copying reconstructs soft-wrapped lines. Hold Shift to use local mouse selection
when a terminal application has enabled mouse reporting.

Pasting text with line breaks or control characters opens a review dialog.
Controls appear as escapes in a shortened preview. Choose Paste to submit the
captured clipboard text, or Cancel to leave the running application untouched.

## Appearance and settings

Open the settings dialog to choose a theme and font, adjust line height and
cursor appearance, and configure shell and terminal permissions. Saved
appearance settings update open panes. Process and buffer settings apply to
new terminals.

The [settings reference](profile-config-toml.md) lists configuration keys,
defaults, and file locations. Restart the app after editing `config.toml`
directly. Built-in keyboard shortcuts follow the platform.

## Keyboard, scrolling, and selection

| Action | Windows | Linux | macOS |
| --- | --- | --- | --- |
| Copy selection | Ctrl+C or Ctrl+Shift+C | Ctrl+Shift+C | Cmd+C |
| Paste | Ctrl+V or Ctrl+Shift+V | Ctrl+Shift+V | Cmd+V |
| New terminal | Ctrl+Shift+T | Ctrl+Shift+T | Cmd+T |
| Close active pane | Ctrl+Shift+W | Ctrl+Shift+W | Cmd+W |
| Search | Ctrl+Shift+F | Ctrl+Shift+F | Cmd+Shift+F |
| Increase / decrease font size | Ctrl+Plus / Ctrl+Minus | Ctrl+Plus / Ctrl+Minus | Cmd+Plus / Cmd+Minus |
| Reset font size | Ctrl+0 | Ctrl+0 | Cmd+0 |
| Next / previous tab | Ctrl+Tab / Ctrl+Shift+Tab | Ctrl+Tab / Ctrl+Shift+Tab | Ctrl+Tab / Ctrl+Shift+Tab |
| Page through history | Shift+Page Up / Shift+Page Down | Shift+Page Up / Shift+Page Down | Shift+Page Up / Shift+Page Down |
| Oldest history / live output | Shift+Home / Shift+End | Shift+Home / Shift+End | Shift+Home / Shift+End |

On Windows, Ctrl+C reaches the running program when nothing is selected. Font
zoom affects only the current pane and is not saved. Reset restores the font
size configured in settings. The equals key also increases the size with the
platform modifier; numeric-keypad plus and minus work too.

Wheel scrolling follows the operating system's scroll amount, and fractional
trackpad motion accumulates without being discarded. Paging retains one row of
context. New output follows the bottom while you are there and preserves your
position when you scroll back; `scroll_on_output` can explicitly enable returning
to the bottom on new output. Accepted terminal input or paste returns to live
output. Terminal mouse reporting takes priority over local scrolling; alternate
screen applications without mouse reporting can receive wheel motion as arrow keys.

Drag to select text, double-click to select a word or path, and triple-click to
select a row. Continuing a double- or triple-click drag extends by whole words
or rows, including when you reverse direction. Alt+drag makes a rectangular
selection. Copying preserves indentation and interior spaces, removes trailing
row blanks, and joins soft wraps for a linear selection. Block selections keep
their row breaks. Selection alone does not overwrite the clipboard.

Hold Shift for local mouse interaction when an application reports mouse input,
unless it explicitly requests Shift capture. Bare Ctrl+A and Ctrl+L remain
available to the shell. Tab and pane shortcuts apply only in the app's own window.

## Companion commands

Native local profiles include three helper commands:

```shell
ketra version
ketra info
ketra config
```

`info` reports the active configuration path; `config` opens that file using
the helper's editor selection. WSL and Ubuntu launcher profiles do not receive
the native helper.

## Completion

KetraTerm suggestions are enabled by default. Use the **Suggestions** settings
page to change inline previews, popup mode, shortcuts, and learning persistence.
The master switch is also available in `config.toml`; restart the app after
editing the file directly:

```toml
[behavior]
smart_suggestions_enabled = true
```

Suggestions need an editable command context from shell integration. Press
Ctrl+Space to request them, use arrows or Page Up/Page Down to navigate, Tab to
accept a deliberately selected item, and Escape to dismiss. Enter also accepts a
deliberately selected suggestion by default. With no deliberate popup selection or
visible inline text, Tab reaches the shell's own completion. When no editable shell context is available,
Ctrl+Space reaches the running application instead.

Suggestions combine built-in command specifications, local files and directories,
and bounded learning. WSL or remote directory reports do not provide a remote
filesystem completion service.

Enable **Inline suggestions** on the Suggestions page to preview the first ranked
completion as ghost text. This is off by default and works even when popups are
disabled. Both surfaces use the same completion sources and ranking. If the top
result would rewrite existing text, it remains popup-only; inline does not skip
to another result.
Right Arrow accepts a visible suffix while the popup is passive; Tab invokes shell completion. Up/Down select
popup items and hide inline text; Tab or Enter then inserts that choice without
execution. Fresh child suggestions remain passive for Tab and Enter: Tab invokes
shell completion and Enter executes the directory already entered. Escape dismisses the popup first, then inline text. See the
[inline contract](../../docs/features/completion.md#inline-suffix-presentation)
for matching and shell-prediction limits.

Choose popup mode **Off**, **On request**, or **Automatic**. Automatic popups
include command names by default; clear **Include command names** to show them
only for parameters. Inline and popup presentation can be enabled independently; the master
switch disables both.

The three shortcut rows control accepting inline text, accepting a selected popup
item, and opening the popup. Click **+**, then press the shortcut to add it. Click
a chip to remove it; removing every chip disables that action. Remove Enter from
**Accept selected popup** to keep Enter available to the shell.

Learning stays in memory unless persistence is enabled explicitly. Persisted
learning can include plaintext command data; filtering cannot identify every
secret. Treat the learning file as sensitive. See the persistence setting in the
[settings reference](profile-config-toml.md#behavior).

## Terminal permissions

Clipboard permissions control requests from programs running in a terminal,
including nested SSH sessions. They are separate from your explicit copy and
paste actions. New configurations allow clipboard writes and ask before reads;
you can change this in settings. Window manipulation is disabled by default.

Invalid explicit clipboard or title permissions deny access and report the
affected key and configuration file. If the whole file cannot be loaded, these
permissions are denied in the recovery configuration as well.

See the [app changelog](../CHANGELOG.md) for changes by release.
