# App settings

This reference covers KetraTerm 0.3.0. Use the settings dialog for live updates;
direct edits to `config.toml` take effect on the next app launch. Saving settings
rewrites the file, including its generated comments.

## Configuration file

The first nonblank override wins: the JVM property `ketraterm.config.path`,
then the environment variable `KetraTerm_CONFIG_PATH`. Both name a file.
Otherwise the app uses:

| Platform                  | Default path                                                                                            |
| ------------------------- | ------------------------------------------------------------------------------------------------------- |
| Windows                   | `%APPDATA%\KetraTerm\config.toml`; without `APPDATA`, `~\.config\ketraterm\config.toml`.                |
| macOS                     | `~/Library/Application Support/KetraTerm/config.toml`.                                                  |
| Linux and other platforms | `$XDG_CONFIG_HOME/ketraterm/config.toml`; without `XDG_CONFIG_HOME`, `~/.config/ketraterm/config.toml`. |

Use section headers and one `key = value` per physical line. The parser accepts
a configuration subset of TOML. Use the generated file as the format template.
For Windows paths, single-quoted strings preserve backslashes.

```toml
[font]
size = 16

[theme]
name = "nord"

[shell]
path = 'C:\Program Files\PowerShell\7\pwsh.exe'
startup_command = 'npm run dev'
```

## Window, font, and theme

| Section  | Key                         | Default          | Accepted values                                                    |
| -------- | --------------------------- | ---------------- | ------------------------------------------------------------------ |
| `window` | `columns`                   | `100`            | Integer, 10..1000.                                                 |
| `window` | `rows`                      | `30`             | Integer, 10..500.                                                  |
| `window` | `scrollback_lines`          | `1000`           | Integer, 0..1000000.                                               |
| `font`   | `family`                    | Platform default | Windows: `Cascadia Mono`; macOS: `Menlo`; elsewhere: `Monospaced`. |
| `font`   | `size`                      | `16`             | Integer points, 10..56.                                            |
| `font`   | `line_height`               | `1.0`            | Multiplier, 0.7..1.5.                                              |
| `font`   | `use_system_fallback_fonts` | `true`           | Boolean.                                                           |
| `theme`  | `name`                      | `"one-dark"`     | `campbell`, `one-dark`, `nord`, `tokyo-night`, `everforest`.       |

Window dimensions and scrollback capacity apply to new terminals.

## Behavior

These keys belong to `[behavior]`.

| Key                                       | Default   | Meaning or accepted values                                   |
| ----------------------------------------- | --------- | ------------------------------------------------------------ |
| `cursor_shape`                            | `"block"` | `block`, `underline`, or `beam`.                             |
| `cursor_blink_millis`                     | `600`     | Integer, 0..10000; zero disables blinking.                   |
| `treat_ambiguous_as_wide`                 | `false`   | East Asian Ambiguous characters occupy two cells.            |
| `audible_bell`                            | `true`    | System beep on BEL.                                          |
| `visual_bell`                             | `true`    | Visual pulse on BEL.                                         |
| `paste_on_middle_click`                   | `true`    | Middle-button clipboard paste.                               |
| `paste_sanitization`                      | `"raw"`   | `raw`, `strip-c0`, or `normalize-line-endings`.              |
| `shell_request_resize_window`             | `false`   | Permit terminal-requested window resizing.                   |
| `shell_request_window_manipulation`       | `false`   | Permit move, minimize, maximize, raise, and lower requests.  |
| `desktop_notifications_enabled`           | `true`    | Permit desktop notifications.                                |
| `smart_suggestions_enabled`               | `false`   | Enable completion and learning.                              |
| `shell_suggestions_enabled`               | `true`    | Show automatic suggestions when completion is enabled.       |
| `accept_selected_suggestion_with_enter`   | `true`    | Enter accepts a selected suggestion.                         |
| `suggestion_learning_persistence_enabled` | `false`   | Persist completion-learning data when completion is enabled. |
| `scroll_on_output`                        | `true`    | Follow new process output.                                   |
| `show_foreground_process_name`            | `true`    | Use detected process names as automatic tab-title fallbacks. |

Completion and persistence switches are available through the file; the 0.3.0
settings dialog hides those controls. Persisted command data can contain
sensitive text.

## Shell

These keys belong to `[shell]`.

| Key               | Default        | Meaning                                                                                               |
| ----------------- | -------------- | ----------------------------------------------------------------------------------------------------- |
| `path`            | Platform shell | Executable path or name. Windows: `powershell.exe`; elsewhere: `$SHELL`, falling back to `/bin/bash`. |
| `start_directory` | User home      | Working directory for new terminals.                                                                  |
| `startup_command` | `""`           | One command line submitted after the first complete interactive prompt; blank disables it.            |

Startup commands require a directly configured interactive PowerShell, Bash,
zsh, or fish shell with hooks enabled. WSL launchers, unsupported shells, and
explicit command/script launches cannot use them. Input before submission
cancels the pending command. The command must fit on one line, contain no
control characters, and be at most 16384 UTF-16 code units.

## Terminal permissions

These keys belong to `[security]`. In 0.3.0, local/remote classification depends
on the launched executable; an SSH command typed inside a local shell retains
the local session's permissions.

| Key                           | Default    | Accepted values                                |
| ----------------------------- | ---------- | ---------------------------------------------- |
| `clipboard_local_write`       | `"prompt"` | `allow`, `prompt`, `allowlist`, `deny`.        |
| `clipboard_remote_write`      | `"deny"`   | `allow`, `prompt`, `allowlist`, `deny`.        |
| `clipboard_read`              | `"deny"`   | `allow`, `prompt`, `allowlist`, `deny`.        |
| `clipboard_max_decoded_bytes` | `1048576`  | Integer, 0..2147483647; decoded payload limit. |
| `title_local_permission`      | `"allow"`  | `allow`, `deny`.                               |
| `title_remote_permission`     | `"deny"`   | `allow`, `deny`.                               |

These permissions govern terminal requests separately from explicit user copy
and paste actions. The app does not configure a clipboard allowlist, so
`allowlist` denies requests.

## Loading and saving

A missing file is created with defaults. Numeric settings are clamped to their
bounds; malformed values generally use the field default. A configuration load
failure attempts to preserve the old file as `<filename>.broken` and write a
default configuration. Settings saves use atomic replacement; a failed save
keeps the active settings unchanged.
