# Profile Configuration Syntax & Path Resolution

The standalone application manages load-time user profiles, color themes, font preferences, and local environment setups stored in a standardized **TOML** configuration file.

---

## 1. TOML Configuration Syntax

The configuration file is divided into clean blocks describing different parts of the workspace environment:

```toml
# Default KetraTerm configuration

[window]
columns = 100             # Grid columns (10..1000)
rows = 30                 # Grid rows (10..500)
scrollback_lines = 1000   # Retained history size (0..1000000)

[font]
family = "Cascadia Mono"          # Monospace font family name
size = 16                         # Font point size (10..56)
line_height = 1.0                 # Line spacing multiplier (0.7..1.5)
use_system_fallback_fonts = true  # Enables fallback system font scan for missing glyphs

[theme]
name = "one-dark"                 # Theme palette name (e.g. one-dark, dracula, nord)

[behavior]
cursor_shape = "block"            # cursor shape: block, underline, beam
cursor_blink_millis = 600         # blink delay (0..10000, 0 means no blink)
treat_ambiguous_as_wide = false   # sets East Asian Ambiguous width rendering policy
audible_bell = true               # play audio beep sound on BEL
visual_bell = true                # show a visual edge pulse on BEL
paste_on_middle_click = true      # paste clipboard on mouse scroll wheel click
paste_sanitization = "preserve"   # preserve or strip-c0; bracketed paste is protected in both
shell_request_resize_window = false # permits running shell scripts to resize the window
shell_request_window_manipulation = false # permits shell scripts to move, minimize, maximize, raise, lower window

[shell]
path = ""                         # Shell path override (empty maps to default shell)
start_directory = ""              # Shell startup directory
startup_command = ""              # Command to run once when each new shell is ready
```

**Paste handling** offers **Preserve text** and **Remove control characters**.
The existing `paste_sanitization` key is retained. Legacy `raw` and
`normalize-line-endings` values load as `preserve`; the next save writes the
canonical value. Local PTY newline behavior remains independent of this setting.
See the [paste contract](../../ketraterm-input/docs/terminal-input-contract.md#paste-and-focus-contract)
for the exact control-character and framing rules.

Set **Startup command** in the standalone settings, or edit `[shell].startup_command`.
For example, `startup_command = 'npm run dev'` starts the project task in each new
terminal. For embedded quotes and Windows paths, the settings writer preserves the
command using escaped TOML strings. Leave the field blank to disable it.
The same encoder preserves quotes, backslashes, whitespace, and control characters
in saved configuration strings; embedded line breaks are escaped onto one physical
line. Startup commands retain their separate single-line restriction.
See the [feature map](../../docs/terminal-feature-map.md#7-embedding--swing-ui)
for shell requirements and execution behavior. IntelliJ uses its own project-local
setting rather than this standalone configuration file.

---

## 2. Directory Resolution Hierarchy

The config manager resolves the location of the `config.toml` file dynamically across operating systems:

1. **System Property Override**:
   * Uses `-Dketraterm.config.path=/path/to/config.toml` if defined.
2. **Environment Variable Override**:
   * Uses the env variable `KetraTerm_CONFIG_PATH=/path/to/config.toml` if defined.
3. **OS-Specific Default Directories**:
   * **Windows**: `%APPDATA%\KetraTerm\config.toml` (falls back to `%USERPROFILE%\.config\ketraterm\config.toml`).
   * **macOS**: `~/Library/Application Support/KetraTerm/config.toml`.
   * **Linux/Unix**: `$XDG_CONFIG_HOME/ketraterm/config.toml` (falls back to `~/.config/ketraterm/config.toml`).

---

## 3. Configuration Backup & Fallback Lifecycle

* **Automatic Creation**: If no config file is found at the resolved path upon loading, the manager creates a default configuration file populated with default properties and comments, saving it to disk for user editing.
* **Soft Failures**: If the TOML file contains invalid syntax or unreadable properties, the config manager logs the warning and falls back gracefully to default values for those specific keys instead of crashing.
