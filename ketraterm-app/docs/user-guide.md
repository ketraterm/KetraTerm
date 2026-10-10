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

## Commands and output

Interactive PowerShell, Bash, zsh, and fish launches receive prompt hooks.
These enable command markers, exit status, navigation between retained commands,
and copying or exporting command output. WSL needs an explicitly selected
supported shell for automatic hooks. Command Prompt and Nushell do not receive
these hooks. Prompt customizations can affect command tracking.

Use terminal search to find text in the visible screen and retained history.
Copying reconstructs soft-wrapped lines. Hold Shift to use local mouse selection
when a terminal application has enabled mouse reporting.

## Appearance and settings

Open the settings dialog to choose a theme and font, adjust line height and
cursor appearance, and configure shell and terminal permissions. Saved
appearance settings update open panes. Process and buffer settings apply to
new terminals.

The [settings reference](profile-config-toml.md) lists configuration keys,
defaults, and file locations. Restart the app after editing `config.toml`
directly. Built-in keyboard shortcuts follow the platform.

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

KetraTerm suggestions are disabled by default. To enable them, add or change
this setting in `config.toml`, then restart the app:

```toml
[behavior]
smart_suggestions_enabled = true
```

The settings dialog does not currently expose this switch. Suggestions need
an editable command context from shell integration. Press Ctrl+Space to request
them, use arrows or Page Up/Page Down to navigate, Tab to accept, and Escape to
dismiss. Enter accepts an already selected suggestion by default. Shell-native
Tab completion is separate.

Suggestions combine built-in command specifications, local files and directories,
and bounded learning. WSL or remote directory reports do not provide a remote
filesystem completion service.

Learning stays in memory unless persistence is enabled explicitly. Persisted
learning can include plaintext command data; filtering cannot identify every
secret. Treat the learning file as sensitive. See the persistence setting in the
[settings reference](profile-config-toml.md#behavior).

## Terminal permissions

Clipboard permissions control requests from programs running in a terminal,
including nested SSH sessions. They are separate from your explicit copy and
paste actions. New configurations allow clipboard writes and ask before reads;
you can change this in settings. Window manipulation is disabled by default.

See the [app changelog](../CHANGELOG.md) for changes by release.
