# Shell support

The standalone app and IntelliJ plugin share local shell discovery and startup
hooks. Library users choose their own process, transport, and shell integration.

## Automatic discovery

Profiles are offered in the order below. A configured shell or explicit launch
command can override the default.

| Platform | Profiles | Discovery |
| --- | --- | --- |
| Windows | Windows PowerShell | System installation, with `powershell.exe` as fallback. |
| Windows | PowerShell | `pwsh.exe` on `PATH`, then PowerShell 7/6 installation directories. |
| Windows | Git Bash | `git-bash.exe` on `PATH` or standard Git installation directories; prefers its `bin/bash.exe`. |
| Windows | WSL | `wsl.exe` on `PATH` or in `System32`; launches the default distribution. |
| Windows | Ubuntu | `ubuntu.exe` on `PATH`. |
| Windows | Command Prompt | `COMSPEC`, with `cmd.exe` as fallback. |
| Linux / macOS | zsh, Bash, fish, Nushell, sh | `PATH`, then `/usr/bin`, `/bin`, `/usr/local/bin`, and `/opt/homebrew/bin`. Only found executables are offered. |

If no Unix shell is discovered, the registry uses the PTY default command.
Discovery does not enumerate WSL distributions. Other shells can be configured
explicitly; recognition alone does not install shell hooks.

## Prompt hooks and completion dialects

Hooks emit OSC 133 prompt/command markers and OSC 7 working-directory reports.
The marker lifecycle is `A` (prompt start), `B` (input start), `C` (command
start), and `D` (command finish, with optional exit status).
They are installed for supported interactive launches when shell integration is
enabled. Explicit script/command execution and incompatible startup flags can
prevent installation.

| Shell / launch | Automatic hooks | Completion dialect | Startup command |
| --- | --- | --- | --- |
| Windows PowerShell / PowerShell | Yes | PowerShell | Yes |
| Bash / Git Bash | Yes | POSIX | Yes |
| zsh | Yes | POSIX | Yes |
| fish | Yes | Plain | Yes |
| WSL / Ubuntu with explicitly selected Bash, zsh, or fish | Yes | POSIX profile mapping | No |
| WSL / Ubuntu with unknown default shell | No | POSIX profile mapping | No |
| sh, dash, ksh, ash | No | POSIX | No |
| Command Prompt, Nushell, unknown shells | No | Plain | No |

The dialect controls completion tokenization and escaping; it is not a complete
shell grammar. Fish deliberately uses conservative plain parsing in direct
profiles. WSL profiles currently use POSIX parsing regardless of their selected
shell. Completion also needs an available editable command context; a dialect
alone does not provide that context. See [completion availability](completion.md#availability).

Startup commands are single-line commands submitted once after a supported
direct shell reports readiness. User input before readiness cancels submission.
They require shell integration and are unavailable for WSL launchers.

PowerShell command-start/finish tracking requires a `PSConsoleHostReadLine`
function to wrap. Without it, the bootstrap still supplies prompt and directory
reports. PowerShell directory reports cover filesystem locations only.

## Features supplied by shell metadata

| Feature | Description |
| --- | --- |
| Prompt decorations | Gutter dots, divider bands, or no decoration. |
| Command navigation | Move between retained commands and select a command block. |
| Command results | Exit status, timestamps, and failed-output indicators. |
| Output extraction | Copy or export retained command output with soft wraps reconstructed. |
| Working directory | Directory-aware titles, local actions, and completion context. |
| Command text | Bounded reconstruction of retained input for completion and learning; ambiguous text remains unavailable. |
| Host observation | Running-command state, metadata revisions, and command-finished/directory-change listeners. |
| Editing context | Prompt readiness, active command-line snapshots, and change signals for context-checked completion edits. |

Markers can also come from a manually configured shell or a host-owned metadata
producer. Startup hooks do not guarantee that every prompt customization will
emit usable metadata. Remote directory reports are metadata, not local paths.

## Launch integration

Profiles carry an executable/argument list, working directory, and environment.
Hosts can also select variables and a PATH prefix to reapply after supported
interactive shell startup files; without hooks, these affect only the initial
process environment. The IntelliJ product uses this for its optional project JDK.
Native standalone profiles also receive the `ketra version`, `ketra info`, and
`ketra config` companion commands. WSL/Ubuntu launchers do not receive that native helper.

See [workspace launch configuration](../../ketraterm-workspace/README.md#profiles-and-launch-options)
and [shell integration](../../ketraterm-shell-integration/README.md) for composition.
