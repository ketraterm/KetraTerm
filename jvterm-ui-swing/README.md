# JvTerm UI Swing (`:jvterm-ui-swing`)

A reusable, premium-tier Swing terminal component built in Kotlin/JVM 21.

`jvterm-ui-swing` translates terminal render frames and keyboard/mouse events into a desktop component (`JComponent`) without knowing which transport (PTY, SSH, WebSocket, etc.) produced the raw stream. It serves as the visual and interactive foundation for standalone desktop terminal apps, IDE tool windows, and custom Swing hosts.

---

## 🔌 Upstream Dependencies
- **`:jvterm-protocol`** (vocabulary, mode IDs, enums)
- **`:jvterm-render-api`** (render frame primitives and color palettes)
- **`:jvterm-render-cache`** (triple-buffered cache reader)
- **`:jvterm-input`** (keyboard/mouse event models)
- **`:jvterm-session`** (session orchestration and lock loops)

---

## 🏛️ Architecture & System Design

The module is built on three core design philosophies:
1. **Complete Protocol Ignorance:** The UI has zero knowledge of ANSI, VT, ESC, OSC, or DCS bytes. It never parses stream protocols or executes grid mutation rules.
2. **Data-Driven Decoupling:** The UI consumes **immutable snapshots** of render frames published from `jvterm-render-cache` and updates state through the `TerminalSession` boundary.
3. **EDT Isolation & Swing Safety:** The Swing component state belongs strictly to the Event Dispatch Thread (EDT). Background rendering and I/O processes interact only through thread-safe snapshot mechanisms.

```mermaid
graph TD
    subgraph Host Application Layer
        Host["Desktop App / IDE Host"] -->|configures| Settings["SwingSettings"]
        Host -->|manages lifecycle| Session["TerminalSession"]
    end

    subgraph jvterm-ui-swing[EDT Confined]
        Terminal["SwingTerminal (JComponent)"]
        ScrollModel["SwingScrollModel"]
        RepaintPlanner["SwingRepaintPlanner"]
        KeyMapper["SwingKeyMapper"]
        
        Terminal -->|registers key/mouse| KeyMapper
        Terminal -->|tracks scrolling| ScrollModel
        Terminal -->|schedules minimal paints| RepaintPlanner
    end

    subgraph Pipeline Boundaries
        Cache["TerminalRenderCache (Snapshot)"]
        SessionBoundary["TerminalSession"]
    end

    Host -->|binds| Terminal
    Terminal -->|reads frame| Cache
    Terminal -->|listener: onDirty| Terminal
    KeyMapper -->|encodes input| SessionBoundary
```

---

## 📖 Sub-Documentation

For detailed specifications on Swing painting and text pipelines:
* [swing-repaint-optimization.md](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-ui-swing/docs/swing-repaint-optimization.md) - Repaint planner bounds calculations, drag selection matrices, and smart path double-click detection.
* [bifurcated-text-rendering.md](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-ui-swing/docs/bifurcated-text-rendering.md) - ASCII fast paths, shaped TextLayout caches, prioritized JBR color emoji fallback chains, and pixel-perfect primitive grid painters.

---

## 🔗 How to Use

To place a functional, interactive terminal component in your Swing layout, instantiate `SwingTerminal` and bind it to your active `TerminalSession`:

```kotlin
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.ui.swing.api.SwingTerminal
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.ui.swing.settings.TerminalTheme
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

fun createTerminalView(session: TerminalSession): JComponent {
    val panel = JPanel(BorderLayout())

    // 1. Define custom, immutable settings (palette, fonts, etc.)
    val settings = SwingSettings(
        palette = TerminalTheme.ONE_DARK.createPalette(),
        fontFamily = "Cascadia Mono",
        fontSize = 15,
        columns = 80,
        rows = 24
    )
    
    // 2. Instantiate the SwingTerminal component
    val terminalComponent = SwingTerminal(
        settingsProvider = { settings }
    )
    
    // 3. Bind the component to the active session
    terminalComponent.bind(session)
    
    panel.add(terminalComponent, BorderLayout.CENTER)
    return panel
}
```

---

## 🔗 How to Extend: Custom Host Services

To integrate clipboard features, hyperlink clicking, or custom alert overlays into the terminal view, implement the [SwingHostServices](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-ui-swing/src/main/kotlin/io/github/jvterm/ui/swing/settings/SwingHostServices.kt) interface:

```kotlin
import io.github.jvterm.ui.swing.settings.SwingHostServices
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class MyCustomHostServices : SwingHostServices {
    override fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    override fun pasteFromClipboard(): String {
        return "Custom pasted string"
    }

    override fun openUrl(url: String) {
        println("User clicked hyperlink: $url")
    }
}
```
