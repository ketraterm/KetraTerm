# JvTerm Workspace (`:jvterm-workspace`)

The `jvterm-workspace` module provides a host-neutral session and tab manager for JvTerm Terminal. It coordinates multiple active terminal sessions (tabs) under a unified workspace lifecycle, maps configurations onto file-based profiles, and implements standard TOML-backed settings persistence.

This module is designed to be completely decoupled from any specific UI toolkit, serving as the headless state controller for tabbed desktop terminal interfaces or IDE tool windows.

---

## Upstream Dependencies
- **`:jvterm-protocol`** (vocabulary, mode IDs, enums)
- **`:jvterm-render-api`** (render frame primitives and color palettes)
- **`:jvterm-transport-api`** (duplex connector contracts)
- **`:jvterm-session`** (session orchestration and lock loops)
- **`:jvterm-pty`** (local PTY process management and options)

---

## Architectural Role

`TerminalWorkspace` manages a collection of `TerminalWorkspaceTab` instances. Each tab wraps an active, running `TerminalSession` tied to a specific `TerminalProfile` launch configuration.

```mermaid
graph TD
    Workspace["TerminalWorkspace"] -->|manages| Tab1["TerminalWorkspaceTab 1"]
    Workspace -->|manages| Tab2["TerminalWorkspaceTab 2"]
    
    Tab1 -->|owns| Session1["TerminalSession"]
    Tab1 -->|describes| Profile1["TerminalProfile (Local Bash)"]
    
    Tab2 -->|owns| Session2["TerminalSession"]
    Tab2 -->|describes| Profile2["TerminalProfile (Local Python)"]

    ConfigManager["TerminalWorkspaceConfigManager"] -->|loads/saves| Config["TerminalConfig"]
    Workspace -.->|updates themes/modes from| Config
```

### Key Components

* **[`TerminalWorkspace`](./src/main/kotlin/io/github/jvterm/workspace/TerminalWorkspace.kt)**: The main lifecycle manager. Handles opening, selecting, closing, and applying settings updates to all open terminal tabs.
* **[`TerminalProfile`](./src/main/kotlin/io/github/jvterm/workspace/TerminalProfile.kt)**: Describes a launch configuration (command, display name, working directory, environment variables).
* **[`TerminalWorkspaceConfigManager`](./src/main/kotlin/io/github/jvterm/workspace/config/TerminalWorkspaceConfigManager.kt)**: Handles loading and saving TOML-based configurations from OS-specific directories, with automatic parsing backups and value clamping.

---

## 🔗 How to Use

The following example shows how to load workspace configurations, register a workspace listener, and open multiple terminal tabs:

```kotlin
import io.github.jvterm.workspace.TerminalWorkspace
import io.github.jvterm.workspace.TerminalWorkspaceListener
import io.github.jvterm.workspace.TerminalWorkspaceTab
import io.github.jvterm.workspace.TerminalWorkspaceOpenOptions
import io.github.jvterm.workspace.TerminalProfile
import io.github.jvterm.workspace.config.TerminalWorkspaceConfigManager
import java.nio.file.Path

fun main() {
    // 1. Resolve configuration and load settings
    val configManager = TerminalWorkspaceConfigManager.getDefault()
    val config = configManager.load()

    // 2. Define a workspace listener to respond to tab lifecycle events
    val listener = object : TerminalWorkspaceListener {
        override fun tabOpened(tab: TerminalWorkspaceTab) {
            println("Tab opened: ${tab.id} - ${tab.title}")
        }
        override fun tabClosed(id: String) {
            println("Tab closed: $id")
        }
        override fun tabSelected(id: String) {
            println("Active tab switched to: $id")
        }
        override fun titleChanged(tab: TerminalWorkspaceTab, title: String) {
            println("Tab ${tab.id} title changed: $title")
        }
        override fun colorChanged(tab: TerminalWorkspaceTab, color: Int) {}
        override fun bell(tab: TerminalWorkspaceTab) {
            println("Alert bell in tab ${tab.id}!")
        }
    }

    // 3. Create the workspace manager
    val workspace = TerminalWorkspace(listener)

    // 4. Declare a launch profile (e.g. Git Shell)
    val gitProfile = TerminalProfile(
        name = "git-shell",
        displayName = "Git Repo Shell",
        command = listOf("bash"),
        environment = mapOf("GIT_PS1" to "true"),
        workingDirectory = Path.of("/my/repo")
    )

    // 5. Open a tab using the profile
    val openOptions = TerminalWorkspaceOpenOptions(
        columns = 80,
        rows = 24,
        maxHistory = config.scrollbackLines,
        treatAmbiguousAsWide = config.treatAmbiguousAsWide
    )
    val tab = workspace.openTab(gitProfile, openOptions)

    // ... When shutting down, closing the workspace closes all active tabs
    workspace.close()
}
```

---

## 🔗 How to Extend: Custom Tab Listeners

UI components (such as Swing tabbed panels or custom IDE interfaces) implement `TerminalWorkspaceListener` to map workspace actions directly onto window views:

```kotlin
import io.github.jvterm.workspace.TerminalWorkspaceListener
import io.github.jvterm.workspace.TerminalWorkspaceTab
import javax.swing.JTabbedPane

class SwingTabAdapter(private val tabbedPane: JTabbedPane) : TerminalWorkspaceListener {
    override fun tabOpened(tab: TerminalWorkspaceTab) {
        // Create Swing component and add tab
        // tabbedPane.addTab(tab.title, component)
    }

    override fun tabClosed(id: String) {
        // Remove Swing component matching ID
    }

    override fun tabSelected(id: String) {
        // Select tab in Swing pane
    }

    override fun titleChanged(tab: TerminalWorkspaceTab, title: String) {
        // Update title of tab
    }

    override fun colorChanged(tab: TerminalWorkspaceTab, color: Int) {}
    override fun bell(tab: TerminalWorkspaceTab) {}
}
```

---

## TOML Settings File Resolution

`TerminalWorkspaceConfigManager` resolves default settings file paths dynamically based on the active operating system:
* **Windows**: `%APPDATA%\JvTerm\config.toml` (falling back to `~/.config/jvterm/config.toml` if env variables are empty).
* **macOS**: `~/Library/Application Support/JvTerm/config.toml`.
* **Linux/Other**: `$XDG_CONFIG_HOME/jvterm/config.toml` (falling back to `~/.config/jvterm/config.toml`).

These files can be overridden using:
- The `jvterm.config.path` JVM system property.
- The `JVTERM_CONFIG_PATH` environment variable.

---

## Testing & Verification

The workspace module has comprehensive unit test coverage under `src/test/kotlin`:
* **`TerminalConfigTest`**: Verifies dynamic path resolution rules, TOML syntax parsing, value clamping bounds, and file format failure recovery backups.
* **`TerminalWorkspaceTest`**: Asserts correct tab opening/closing states, settings propagation rules, and event bridge dispatches.

To run checks for this module:
```bash
./gradlew :jvterm-workspace:test
```
