# Persistent Terminal Storage Layout

This document describes how KetraTerm stores local configuration, backups, and
command-completion learning data. Stored command data is intended for ranking
local suggestions only; raw terminal stdout/stderr is never saved.

## Directory Resolution Hierarchy

The workspace configuration path is resolved by
`TerminalWorkspaceConfigManager` in this order:

1. System property override:
   `-Dketraterm.config.path=/path/to/config.toml`.
2. Environment variable override:
   `KetraTerm_CONFIG_PATH=/path/to/config.toml`.
3. OS-specific default directories:
   Windows uses `%APPDATA%\KetraTerm\config.toml`, with a fallback to
   `%USERPROFILE%\.config\ketraterm\config.toml`.
   macOS uses `~/Library/Application Support/KetraTerm/config.toml`.
   Linux/Unix uses `$XDG_CONFIG_HOME/ketraterm/config.toml`, with a fallback to
   `~/.config/ketraterm/config.toml`.

Backup and completion-learning files are stored next to the resolved
`config.toml`.

## Files

### `config.toml`

Stores load-time profiles, theme/font preferences, terminal sizing, behavior
settings, and security policy settings. Missing or invalid values fall back to
safe defaults. If no config exists, KetraTerm creates a default file with
comments.

### `config.toml.broken`

If the config manager encounters a fatal parse error, it copies the malformed
file to `config.toml.broken`, then writes clean defaults so the app can start.

### `command-completion-stats-v1.tsv`

An opt-in, compact suggestion-learning index. This is not raw shell history.
The file stores aggregate exact-command counters, privacy-preserving command
shape counters, and source-specific suggestion feedback counters used by the
completion engine:

```tsv
KetraTerm_COMMAND_COMPLETION_STATS	3
C	<commandBase64>	<normalizedBase64>	<profileBase64>	<cwdBase64>	<useCount>	<successCount>	<failureCount>	<acceptedCount>	<dismissedCount>	<lastUsedEpochMillis>
S	<executableBase64>	<subcommandsBase64List>	<optionNamesBase64List>	<positionalArgumentCount>	<optionValueCount>	<shapeKeyBase64>	<profileBase64>	<cwdBase64>	<useCount>	<successCount>	<failureCount>	<acceptedCount>	<dismissedCount>	<lastUsedEpochMillis>
F	<sourceBase64>	<candidateKind>	<tokenPosition>	<replacementStartOffset>	<replacementEndOffset>	<profileBase64>	<cwdBase64>	<acceptedCount>	<dismissedCount>	<lastUsedEpochMillis>
```

Text fields are Base64URL-encoded without padding so tabs and Unicode text do
not corrupt the TSV layout. Writes are offloaded to a single daemon worker and
committed through atomic replacement when the filesystem supports it.

## Security And Secret Filtering

Persistent suggestion learning is disabled by default. It can be enabled with
`persistent_suggestion_learning_enabled = true` under `[behavior]` in
`config.toml`. KetraTerm does not store a raw command-history file.

Before any exact command or shape row is recorded or persisted, the standalone
host applies `CommandPersistencePrivacyPolicy`:

1. Commands starting with a space or tab are ignored, matching the common shell
   `HISTCONTROL=ignorespace` convention.
2. Blank and multi-line commands are ignored.
3. Commands and shape vocabulary containing sensitive substrings are ignored,
   including password/passwd, secret, token, apikey/api_key, private_key,
   access_key, secret_key, bearer, authorization, credential/credentials,
   passcode, passphrase, jwt, key markers, and auth markers.

Shape rows intentionally avoid raw positional argument values. They store only
the executable, bounded known subcommand vocabulary, option names, argument
counts, and ranking counters.
