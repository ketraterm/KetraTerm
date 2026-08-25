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

Standalone backup and completion-learning files are stored next to the resolved
`config.toml`. The IntelliJ plugin stores its application-level completion index under the IDE system directory at
`ketraterm/command-completion-stats-v1.tsv`.

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

For both standalone and IntelliJ this is an opt-in compact suggestion-learning
index rather than a replayable terminal transcript. It stores sanitized
aggregate exact-command counters used by the completion engine:

```tsv
KetraTerm_COMMAND_COMPLETION_STATS	1
C	<commandBase64>	<profileBase64>	<cwdBase64>	<useCount>	<successCount>	<failureCount>	<acceptedCount>	<dismissedCount>	<lastUsedEpochMillis>
```

Text fields are Base64URL-encoded without padding so tabs and Unicode text do
not corrupt the TSV layout. Base64URL is not encryption. The decoder accepts
only the header and exact `C` rows shown above; unsupported or malformed rows
reject the complete file.

`TerminalCompletionLearningCoordinator` is the single runtime owner. Each
bounded learning event updates memory before its recording call returns, so
ranking does not wait for hydration or disk. Hydration merges atomically with
live rows before any snapshot is written. One worker observes a conflated dirty
signal and checkpoints the latest immutable snapshot every 30 seconds while
dirty. Continuous command activity does not postpone that checkpoint, and
shutdown forces the final dirty write. The coordinator talks directly to one
bounded fixed-path file store, whose strict codec and atomic replacement run on
the I/O dispatcher. There is no repository, child writer, control-command
queue, per-event snapshot, or general flush operation.

Each product supplies one fixed destination when it creates the coordinator and
only toggles persistence on or off. Runtime path switching and cross-file import
semantics are intentionally unsupported. Loads, rows, line sizes, and total file
bytes are bounded before decoding or encoding, so neither startup nor settings
changes read this file on the Swing event-dispatch thread. Derived matching keys
are recomputed by completion models rather than stored as additional fields.
The coordinator merges this one file with live in-memory learning; callers must
not separately preload the same aggregate file.

## Security And Secret Filtering

Standalone persistent suggestion learning is disabled by default. It can be enabled with
`suggestion_learning_persistence_enabled = true` under `[behavior]` in
`config.toml`. KetraTerm does not store a raw command-history file. Older
configs using `persistent_suggestion_learning_enabled` or
`persistent_command_history_enabled` are accepted as load-only compatibility
fallbacks, but new saves write only `suggestion_learning_persistence_enabled`.
IntelliJ exposes **Remember learned suggestions across IDE restarts** and keeps
it disabled by default. When a product starts disabled, it neither loads nor
writes this file. Disabling at runtime synchronously prevents new writes and
invalidates a pending checkpoint; an atomic file operation already in progress
may finish. Hydration that already started may also finish and merge because
disabling does not clear in-memory learning. The first enable hydrates the fixed
file once, and later toggles do not reload it. A rejected or unreadable file is
not overwritten during that lifecycle. Session MRU and in-memory learning remain active. The plugin's
enabled store lives in the IDE system directory described above.

Before any exact-command row enters persistent learning, the shared coordinator
applies `TerminalCompletionPersistencePolicy`:

1. Commands starting with a space or tab are ignored, matching the common shell
   `HISTCONTROL=ignorespace` convention.
2. Blank and multi-line commands are ignored.
3. Commands containing sensitive substrings are ignored,
   including password/passwd, secret, token, apikey/api_key, private_key,
   access_key, secret_key, bearer, authorization, credential/credentials,
   passcode, passphrase, jwt, key markers, and auth markers.

Exact rows retain command text and optional profile and
working-directory context. The filters block common accidental disclosures but
cannot recognize every argument, path, URL, credential, or user-defined secret.
Treat `command-completion-stats-v1.tsv` as sensitive local command-derived data
and protect it with normal filesystem permissions.
