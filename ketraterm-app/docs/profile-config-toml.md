# Profile Configuration Syntax & Path Resolution

The standalone application stores one preferences snapshot in `config.toml`.
Use the settings dialog for live updates. Direct file edits take effect on the
next application launch. This file belongs to the standalone product; embedded
libraries and the IntelliJ plugin have their own configuration owners.

## 1. TOML Configuration Syntax

The application accepts section headers, one `key = value` per physical line,
booleans, numbers, quoted strings, and comments. Use the generated file as the
format template: the parser implements this configuration subset, rather than
the full TOML language. Arrays, inline tables, and multiline values are not
configuration values.

A small configuration can override only the preferences you need:

```toml
[window]
columns = 120
rows = 35
scrollback_lines = 5000

[font]
size = 16
line_height = 1.0

[theme]
name = "nord"

[shell]
path = "bash"
startup_command = ""

[security]
clipboard_write = "allow"
clipboard_read = "prompt"
title_permission = "allow"
```

### Window, font, and theme

| Section | Key | Default | Accepted values |
| --- | --- | --- | --- |
| `window` | `columns` | `100` | Integer, 10..1000. |
| `window` | `rows` | `30` | Integer, 10..500. |
| `window` | `scrollback_lines` | `1000` | Integer, 0..1000000. |
| `font` | `family` | Platform default | Installed family name. Windows: `Cascadia Mono`; macOS: `Menlo`; elsewhere: `Monospaced`. |
| `font` | `size` | `16` | Integer points, 10..56. |
| `font` | `line_height` | `1.0` | Multiplier, 0.7..1.5. |
| `font` | `use_system_fallback_fonts` | `true` | Boolean; permits installed system fonts as fallback. |
| `theme` | `name` | `one-dark` | `campbell`, `one-dark`, `nord`, `tokyo-night`, `everforest`. |

Unknown theme identifiers use `one-dark` at rendering time. Font selection uses
Swing's font-family resolution and fallback rules. Window and scrollback values
are initial settings for new terminals, rather than requests to replace an
existing process or history buffer.

### Behavior

All keys below belong to `[behavior]`. Boolean values are `true` or `false`.

| Key | Default | Meaning or accepted values |
| --- | --- | --- |
| `cursor_shape` | `"block"` | `block`, `underline`, or `beam`; unknown values render as a block. |
| `cursor_blink_millis` | `600` | Integer, 0..10000; zero disables blinking. |
| `prompt_decoration` | `"gutter"` | `gutter`, `divider`, or `none`, ignoring case. |
| `treat_ambiguous_as_wide` | `false` | East Asian Ambiguous characters occupy two cells. |
| `audible_bell` | `true` | System beep on BEL. |
| `visual_bell` | `true` | Visual pulse on BEL. |
| `paste_on_middle_click` | `true` | Middle-button gesture pastes ordinary clipboard text. |
| `paste_sanitization` | `"preserve"` | `preserve` or `strip-c0`; bracketed-paste framing is protected in both. |
| `shell_request_resize_window` | `false` | Permit terminal-originated grid-size requests to resize the window. |
| `shell_request_window_manipulation` | `false` | Permit terminal-originated move, minimize, maximize, raise, and lower requests. |
| `desktop_notifications_enabled` | `true` | Permit product notifications from OSC 9 and OSC 777 events. |
| `smart_suggestions_enabled` | `false` | Master switch for completion and learning. |
| `shell_suggestions_enabled` | `true` | Automatic suggestions when the master switch is enabled. Explicit requests need only the master switch. |
| `accept_selected_suggestion_with_enter` | `true` | Enter accepts an already selected suggestion; otherwise it reaches the shell. |
| `suggestion_learning_persistence_enabled` | `false` | Persist compact completion-learning metadata when completion is enabled. |
| `scroll_on_output` | `true` | Follow new process output. |
| `show_foreground_process_name` | `true` | Use a detected process name when it does not supply a title; custom names retain priority. |

Paste handling is independent of the local PTY's newline policy. Legacy `raw`
and `normalize-line-endings` values load as `preserve`; saving writes the
canonical value. See the [paste contract](../../ketraterm-input/docs/terminal-input-contract.md#paste-and-focus-contract)
for control-character and framing rules.

Persisted completion data is stored beside the configuration file and contains
sanitized learning metadata. See the [persistence module](../../ketraterm-completion-persistence/Module.md)
for its storage and lifecycle contract.

### Shell launch and startup command

| `[shell]` key | Default | Meaning |
| --- | --- | --- |
| `path` | Platform shell | One executable path or command name. Windows: `powershell.exe`; elsewhere: `$SHELL`, falling back to `/bin/bash`. A blank value uses this default. |
| `start_directory` | User home | Working directory for new terminals. |
| `startup_command` | `""` | One shell command line to submit after the first complete interactive prompt; blank disables it. |

`path` is not a shell command line containing arguments. Use application launch
arguments for a one-off process command. Discovered profiles may supply their
normal shell flags when the executable matches a known profile.

For example, `startup_command = 'npm run dev'` runs once in each new terminal.
It requires a directly configured interactive PowerShell, Bash, zsh, or fish
shell with startup hooks enabled. WSL launchers, unsupported shells, and explicit
command/script entry points cannot use it; incompatible launches report an
error. User input before submission cancels the pending command. Commands are
interpreted by the selected shell, with no additional host quoting or expansion.
Use a script for multiline programs. The limit is 16384 UTF-16 code units, with
no control characters.

For Windows paths, single-quoted literal strings preserve backslashes:
`path = 'C:\tools\pwsh.exe'`. Keep each value on one physical line. The settings
writer preserves quotes, significant whitespace, Unicode, and backslashes;
values requiring escapes use the parser's supported triple-quoted form. Saving
replaces the file with canonical generated content, so custom comments and
unknown keys are not retained.

### Terminal-originated permissions

All keys below belong to `[security]`. Permissions cover the whole terminal
session, including nested SSH and multiplexer output.

| Key | New-file default | Accepted values |
| --- | --- | --- |
| `clipboard_write` | `"allow"` | `allow`, `prompt`, `deny`. |
| `clipboard_read` | `"prompt"` | `allow`, `prompt`, `deny`. Existing files with a missing or invalid value use `deny`. |
| `clipboard_max_decoded_bytes` | `1048576` | Integer, 0..2147483647; decoded UTF-8 limit for clipboard reads and writes. |
| `title_permission` | `"allow"` | `allow`, `deny`. |

The settings dialog labels `prompt` as Ask. These permissions govern terminal
requests, rather than the user's explicit copy or paste actions. Removed
local/remote permission keys are ignored; use the session-wide keys above.

## 2. Directory Resolution Hierarchy

The first nonblank override wins:

1. JVM system property `ketraterm.config.path`.
2. Environment variable `KetraTerm_CONFIG_PATH`.
3. Platform default shown below.

| Platform | Configuration file |
| --- | --- |
| Windows | `%APPDATA%\KetraTerm\config.toml`; without `APPDATA`, `~\.config\ketraterm\config.toml`. |
| macOS | `~/Library/Application Support/KetraTerm/config.toml`. |
| Linux and other platforms | `$XDG_CONFIG_HOME/ketraterm/config.toml`; without `XDG_CONFIG_HOME`, `~/.config/ketraterm/config.toml`. |

Override values name the file, rather than its parent directory. The app's
`ketra info` companion command reports the active path; `ketra config` opens it
through the helper's editor selection. Native helpers are not installed into
WSL or Ubuntu launcher profiles.

## 3. Configuration Backup & Fallback Lifecycle

- A missing file is generated with defaults, including clipboard reads set to
  `prompt`. If generation fails, the app reports the write failure and uses the
  defaults in memory.
- Numeric values outside their bounds are clamped, including integer overflow.
  Malformed numbers and invalid booleans use the corresponding field default.
  Existing files use `deny` for missing or invalid clipboard-read permissions.
- A parse or configuration-validation failure moves the existing file to
  `<filename>.broken`, replacing any previous backup, then attempts to write a
  default file. These recovery defaults deny clipboard reads. A failed backup
  leaves the original file in place and returns the recovery defaults.
- An I/O read failure is reported and uses recovery defaults without backing up
  or replacing the unreadable file.
- Settings saves stage a complete snapshot beside the destination and require
  atomic replacement. A failed save preserves the previous file and active
  settings. Successful saves publish the snapshot to consumers on the EDT.

See [README.md](../README.md) for application launch and
[AGENTS.md](../AGENTS.md) for maintainer ownership and validation.
