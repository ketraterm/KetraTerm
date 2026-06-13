# 🌀 JvTerm Terminal

**JvTerm** (derived from **JVM** + **Terminal**) is a next-generation, high-performance, strictly modular terminal emulator library written in **Kotlin/JVM 21**. 

Designed for embedding into IDEs, developer tools, and standalone desktop applications, JvTerm provides a clean, fast, and modern terminal architecture. It rejects the bloated legacy compatibility of the 1980s (like printer passthroughs or Tektronix vector graphics) to focus on contemporary shells and text-user interfaces (TUIs).

---

## Key Features & Strengths

* **Modern & Fast**: Optimized with zero-allocation hot paths, table-driven FSM transitions, and flat primitive grids. Sustains a rendering speed of **60+ FPS** under intense stdout streams.
* **TUI-Ready Compliance**: Passes most of the tests from `vttest`, ensuring flawless rendering and interactive support for advanced TUIs such as `vim`, `htop`, `tmux`, and `less`.
* **Native PTY Integration**: Backed by JetBrains [Pty4J](https://github.com/traff/pty4j) under the hood, enabling seamless, cross-platform local shell execution (`cmd.exe` or `powershell.exe` on Windows; `bash` or `zsh` on macOS/Linux).
* **Premium Swing Component**: Exposes a highly customizable Java Swing component (`SwingTerminal`) that is easy to drop into any desktop layout.
* **Triple-Buffered UI Rendering**: Uses a double/triple-buffered publisher model to isolate background PTY processing from the Swing Event Dispatch Thread (EDT), ensuring **zero visual tearing and glitch-free painting**.
* **Precision Typography & Unicode**: Fully implements Unicode Standard Annex #29 (UAX #29) grapheme cluster segmentation, East Asian width policies, and custom cell-by-cell box-drawing renderers.
* **Security-Hardened Design**: Includes bounded LRU caches for OSC 8 hyperlinks, title stack guards, and strict DCS/OSC byte limits to protect hosts from memory exhaustion.

---

## Seamless Integration Guide

Integrating a local shell into a Swing application requires only a few lines of configuration:

```kotlin
import io.github.jvterm.pty.TerminalSessions
import io.github.jvterm.pty.PtyOptions
import io.github.jvterm.ui.swing.api.SwingTerminal
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.ui.swing.settings.TerminalTheme
import java.awt.BorderLayout
import javax.swing.JFrame

fun spawnTerminalWindow() {
    // 1. Configure the local PTY process (automatically resolves default platform shell)
    val options = PtyOptions(
        command = emptyList(), // e.g. resolves to bash/zsh/cmd.exe
        columns = 100,
        rows = 30,
        maxHistory = 2000
    )
    
    // 2. Start PTY process and reader daemon threads
    val session = TerminalSessions.localPty(options)
    
    // 3. Create the Swing JComponent with visual settings
    val settings = SwingSettings(
        palette = TerminalTheme.ONE_DARK.createPalette(),
        fontSize = 14,
        fontFamily = "JetBrains Mono"
    )
    val terminalComponent = SwingTerminal(
        settingsProvider = { settings }
    )
    
    // 4. Bind the Swing component to the active session
    terminalComponent.bind(session)
    
    // 5. Host it in a standard JFrame layout
    val frame = JFrame("JvTerm Terminal")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.layout = BorderLayout()
    frame.add(terminalComponent, BorderLayout.CENTER)
    frame.pack()
    frame.isVisible = true
}
```

---

## Project Structure

JvTerm is composed of 12 highly decoupled Gradle modules:

* **`:jvterm-protocol`**: Zero-dependency ANSI/DEC constants and vocabulary enums.
* **`:jvterm-parser`**: Streaming UTF-8 decoder and table-driven escape sequence FSM.
* **`:jvterm-core`**: Headless text grid storage, circular scrollback buffers, and resizing reflow.
* **`:jvterm-host`**: Semantic translation adapter connecting the parser to core state.
* **`:jvterm-input`**: Keyboard/mouse event models and host-bound ANSI encoders.
* **`:jvterm-render-api`**: Dependency-free visual frame contracts.
* **`:jvterm-render-cache`**: Double/triple-buffered publication cache.
* **`:jvterm-transport-api`**: Duplex I/O connector interfaces.
* **`:jvterm-session`**: Thread synchronization, lock controls, and event loop.
* **`:jvterm-pty`**: Local native process Pty4J launcher and stream pump.
* **`:jvterm-ui-swing`**: Reusable desktop `JComponent` painter and mouse interaction adapters.
* **`:jvterm-testkit`**: In-memory connector mocks and simulation tools.

> [!TIP]
> For a detailed walkthrough of the unidirectional pipeline flow, concurrency locks, in-memory cell storage, and caches, refer to our [Architecture Guide](ARCHITECTURE.md).

---

## Development & Verification

### Prerequisites
* **JDK 21 or higher**
* **Gradle 7.4+**

### Command Reference
* **Run All Tests**:
  ```bash
  ./gradlew test
  ```
* **Run Component Checks**:
  ```bash
  ./gradlew :jvterm-parser:test
  ./gradlew :jvterm-core:test
  ./gradlew :jvterm-ui-swing:test
  ```
* **Launch Local PTY Swing Demo**:
  ```bash
  ./gradlew :jvterm-ui-swing-demo:run
  ```
* **Launch with Custom Shell Command**:
  ```bash
  ./gradlew :jvterm-ui-swing-demo:run --args="powershell.exe"
  ```

---

## License

Copyright 2026 Gagik Sargsyan

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.