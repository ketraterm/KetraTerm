# Profile Configuration Syntax & Path Resolution

The `jvterm-workspace` module manages load-time user profiles, color themes, font preferences, and local environment setups stored in a standardized **TOML** configuration file.

---

## 1. TOML Configuration Syntax

The configuration file is divided into clean blocks describing different parts of the workspace environment:

```toml
# Default JvTerm configuration

[window]
columns = 80              # Grid columns (10..500)
rows = 24                 # Grid rows (5..300)
scrollback_lines = 1000   # Retained history size (0..100000)

[font]
family = "Cascadia Mono"          # Monospace font family name
size = 14                         # Font point size (8..72)
line_height = 1.2                 # Line spacing multiplier (1.0..2.5)
use_system_fallback_fonts = true  # Enables fallback system font scan for missing glyphs

[theme]
name = "one-dark"                 # Theme palette name (e.g. one-dark, dracula, nord)

[behavior]
cursor_shape = "block"            # cursor shape: block, underline, bar
cursor_blink_millis = 500         # blink delay (0..5000, 0 means no blink)
treat_ambiguous_as_wide = false   # sets East Asian Ambiguous width rendering policy
audible_bell = false              # play audio beep sound on BEL
paste_on_middle_click = true      # paste clipboard on mouse scroll wheel click
shell_request_resize_window = true# permits running shell scripts to resize the window

[shell]
path = ""                         # Shell path override (empty maps to default shell)
start_directory = ""              # Shell startup directory
```

---

## 2. Directory Resolution Hierarchy

The config manager resolves the location of the `config.toml` file dynamically across operating systems:

1. **System Property Override**:
   * Uses `-Djvterm.config.path=/path/to/config.toml` if defined.
2. **Environment Variable Override**:
   * Uses the env variable `JVTERM_CONFIG_PATH=/path/to/config.toml` if defined.
3. **OS-Specific Default Directories**:
   * **Windows**: `%APPDATA%\JvTerm\config.toml` (falls back to `%USERPROFILE%\.config\jvterm\config.toml`).
   * **macOS**: `~/Library/Application Support/JvTerm/config.toml`.
   * **Linux/Unix**: `$XDG_CONFIG_HOME/jvterm/config.toml` (falls back to `~/.config/jvterm/config.toml`).

---

## 3. Configuration Backup & Fallback Lifecycle

* **Automatic Creation**: If no config file is found at the resolved path upon loading, the manager creates a default configuration file populated with default properties and comments, saving it to disk for user editing.
* **Soft Failures**: If the TOML file contains invalid syntax or unreadable properties, the config manager logs the warning and falls back gracefully to default values for those specific keys instead of crashing.
