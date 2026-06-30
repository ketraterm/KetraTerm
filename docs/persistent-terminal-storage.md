# Persistent Terminal Storage Layout

This document describes how KetraTerm stores configurations, back-ups, and command histories on the local filesystem, including directories resolved on different operating systems and the built-in security filters that prevent credentials from leaking to disk.

---

## 1. Directory Resolution Hierarchy

The path to the user configuration directory is resolved dynamically by the `ketraterm-workspace` configuration manager in the following order of precedence:

1. **System Property Override**:
   * Uses `-Dketraterm.config.path=/path/to/config.toml` if defined.
2. **Environment Variable Override**:
   * Uses the environment variable `KetraTerm_CONFIG_PATH=/path/to/config.toml` if defined.
3. **OS-Specific Default Directories**:
   * **Windows**: `%APPDATA%\KetraTerm\config.toml` (falls back to `%USERPROFILE%\.config\ketraterm\config.toml`).
   * **macOS**: `~/Library/Application Support/KetraTerm/config.toml`.
   * **Linux/Unix**: `$XDG_CONFIG_HOME/ketraterm/config.toml` (falls back to `~/.config/ketraterm/config.toml`).

The command history and backup files are stored in the same parent directory resolved for `config.toml`.

---

## 2. File Specifications

KetraTerm persists three main files under the configuration directory:

### A. `config.toml` (Workspace Configuration)
Stores load-time user profiles, color themes, font preferences, terminal sizes, and behavior settings in TOML format.
* **Fallback Behavior**: If a configuration key is missing or invalid, KetraTerm logs a warning and falls back to safe system defaults.
* **Automatic Creation**: If no config file is found at the resolved path, a default file is created with extensive comments.

### B. `config.toml.broken` (Backup File)
If the configuration manager encounters a fatal parsing error or malformed structure in `config.toml` upon load:
1. It copies the malformed file to `config.toml.broken` (overwriting any previous backup) to prevent user data loss.
2. It resets `config.toml` to clean defaults to allow the terminal emulator to start safely.

### C. `command-history-v1.tsv` (Command History Store)
An opt-in, bounded history of completed command executions. Raw terminal stdout/stderr is **never** saved.
* **Format**: Versioned, tab-separated values (TSV) format.
  ```tsv
  KetraTerm_COMMAND_HISTORY	1
  <startedAt>	<finishedAt>	<exitCode>	<profileIdBase64>	<workingDirectoryBase64>	<commandBase64>
  ```
* **Text Encoding**: Text fields (`profileId`, `workingDirectoryUri`, `command`) are Base64URL-encoded (without padding) to preserve command newlines and tab characters without corrupting the TSV layout.
* **IO Lifecycle**: Writes are offloaded to a background daemon thread (`ketraterm-command-history`) using atomic replacements (`ATOMIC_MOVE` falling back to standard replace) to ensure terminal thread performance is not impacted by disk I/O.

---

## 3. Security and Secret Filtering

To prevent API keys, private tokens, passwords, and sensitive environment variables from being persisted in plaintext (Base64 is encoding, not encryption), KetraTerm implements the following automatic filtering rules before any command is written to `command-history-v1.tsv`:

1. **Opt-in Only**:
   * Persistent command history is disabled by default. It can be enabled by setting `persistent_command_history_enabled = true` under the `[behavior]` block in `config.toml`.
2. **Ignorespace Convention**:
   * Any command starting with a space (` `) or tab (`\t`) character is bypassed and **never** recorded. This aligns with standard shell `HISTCONTROL=ignorespace` behavior.
3. **Credential Keyword Filter**:
   * The command string is scanned case-insensitively. If it contains any of the following substrings, recording is skipped:
     * `password` / `passwd`
     * `secret`
     * `token`
     * `apikey` / `api_key`
     * `private_key`
     * `access_key`
     * `secret_key`
     * `bearer`
     * `authorization`
     * `credentials`
     * `passcode`
     * `passphrase`
