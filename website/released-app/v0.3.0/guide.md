# Using KetraTerm

This guide covers KetraTerm 0.3.0. Platform packages include a Java runtime;
the Java-preinstalled archive requires Java 25.

## Shells and sessions

Choose an installed shell from the profile menu when opening a terminal.
Use tabs for separate sessions and split panes to keep sessions side by side.
Tabs can have custom names and accent colors. Sessions are not restored after
restarting the app.

## Commands and output

Interactive PowerShell, Bash, zsh, and fish launches receive prompt hooks for
command markers, exit status, navigation between retained commands, and copying
or exporting command output. Prompt customizations can affect command tracking.
Command Prompt and Nushell do not receive these hooks.

Terminal search covers the visible screen and retained history. Hold Shift to
use local mouse selection when a terminal application has enabled mouse reporting.

## Appearance and settings

Open settings to choose a theme and font, adjust line height and cursor
appearance, and configure the shell and terminal permissions. Saved appearance
settings update open panes; process and buffer settings apply to new terminals.

The [settings reference](settings.md) lists configuration keys and defaults.
Restart the app after editing `config.toml` directly.

## Completion

KetraTerm suggestions are disabled by default. To enable them, set
`smart_suggestions_enabled = true` under `[behavior]` in `config.toml`, then
restart the app. The settings dialog does not expose this switch in 0.3.0.

Suggestions require an editable command context from shell integration. Press
Ctrl+Space to request them, navigate with arrows or Page Up/Page Down, accept
with Tab, and dismiss with Escape. Enter accepts an already selected suggestion
by default. Shell-native Tab completion works independently.

Suggestions combine built-in command specifications, local files and directories,
and bounded learning. Remote and WSL paths do not provide a remote filesystem
completion service. Learning stays in memory unless persistence is explicitly
enabled; persisted command data can contain sensitive text.

## Terminal permissions

Clipboard permissions govern requests from terminal programs separately from
your explicit copy and paste actions. In 0.3.0, clipboard writes ask by default
for local launches and are denied for direct SSH launches; reads are denied.
The local/remote classification is based on the launched process: starting SSH
inside a local shell does not change it.

Window manipulation and terminal-requested window resizing are disabled by
default. See the [app changelog](../../../ketraterm-app/CHANGELOG.md) for
released changes.
