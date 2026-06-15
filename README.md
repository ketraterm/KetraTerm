# JvTerm Terminal

**JvTerm** (derived from **JVM** + **Terminal**) is a next-generation, high-performance, strictly modular terminal emulator library written in **Kotlin/JVM 21**.

Designed for embedding into IDEs, developer tools, and standalone desktop applications, JvTerm provides a clean, fast, and modern terminal architecture. It rejects the bloated legacy compatibility of the 1980s (like printer passthroughs or Tektronix vector graphics) to focus on contemporary shells and text-user interfaces (TUIs).


---

## Features

* **Native Pseudo-Terminal (PTY) Integration**: Seamless cross-platform native execution using JetBrains [Pty4J](https://github.com/traff/pty4j) with full Windows ConPTY support, built to handle modern shells (Zsh, Fish, PowerShell) and prompt size propagation.
* **Modern TUI & vt100/xterm Compliance**: Passes most tests of the rigorous `vttest` suite, ensuring flawless rendering for heavy interactive TUI applications like Neovim, Tmux, Htop, Fzf, and lazygit.
* **Richer Styling & 24-Bit TrueColor**: Bypasses the limits of standard 256-color palettes with full 24-bit TrueColor RGB mapping. Renders overline decorations and modern underline styles (Single, Double, Curly, Dotted, Dashed) with custom underline colors.
* **Advanced Keyboard Shortcuts**: Full support for the **Kitty Keyboard Protocol** (capturing complex shortcut transitions like press, repeat, release, and alternate layout shortcuts that outdated terminal widgets drop) alongside xterm `modifyOtherKeys` and compact CSI-u.
* **Unbounded Mouse Tracking**: Supports legacy mouse tracking alongside modern Standard SGR Mouse (`1006`) and URXVT (`1015`) decimal-packed mouse coordinates, allowing clicks, drags, and scroll-wheel interactions to work on high-resolution displays larger than 223 columns.
* **Tear-Free Triple-Buffered Rendering**: Decouples active parsing from the UI drawing loops using an asynchronous dirty-coalescing rendering worker and a triple-buffered publisher. Delivers a clean **60+ FPS** paint cycle with zero visual tearing or stuttering under massive log outputs.
* **Security-Hardened Design**: Hardened against malicious escape sequence exploits. Utilizes a bounded, double-indexed LRU cache for OSC 8 hyperlinks, strict xterm title stack limits, and pre-allocated OSC/DCS payload buffers to block memory exhaustion.
* **Pixel-Perfect Typography & Color Emojis**: Integrates UAX #29 grapheme cluster segmentation, East Asian width policies, custom fallback font chains, and prioritized OS color emojis (Apple, Segoe, Noto). Programmatically paints box-drawing and block characters to eliminate anti-aliased line gaps.
* **Zero-Allocation Memory Profile**: Core grid storage is built on flat parallel primitive arrays (no object-per-cell overhead) and a circular arena allocator (`ClusterStore`), ensuring near-zero garbage collector pressure and pauses during active shell throughput.
* **Independent Buffer & Margin Physics**: Employs vertical and horizontal scroll margins (`DECSLRM`/`DECSTBM`) with instant switching between primary and alt buffers (`?1049`) carrying independent margins and cursor state save slots.
* **Native Desktop Notifications**: Fully supports native desktop notifications triggered directly via iTerm2-style `OSC 9` and urxvt-style `OSC 777` sequences, featuring a JVTerm-specific severity extension (`info`, `warning`, `error`, `none`), ConEmu subcommand conflict filtering, and self-cleaning tray icon management.

> For a complete specification of all supported capabilities, see the [Terminal Feature Map](docs/terminal-feature-map.md). A detailed list of current backlog items and compatibility decisions is maintained in the [Terminal Feature Gap Map](docs/terminal-feature-gap-map.md)

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

> [TIP]
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

## Authors

* **Gagik Sargsyan** - Creator & Core Maintainer

---

## Links & Resources

* **Terminal Protocol Details**: [xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)
* **Parser FSM Inspiration**: [Paul Williams' ANSI Parser State Machine](https://vt100.net/emu/dec_ansi_parser)
* **Unicode Segmentation Standard**: [Unicode Standard Annex #29 (UAX #29)](https://www.unicode.org/reports/tr29/)
* **Advanced Keyboard Input Specs**: [Kitty Keyboard Protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/)
* **Native PTY Dependency**: [Pty4J Github Repository](https://github.com/traff/pty4j)
* **Terminal Testing Reference**: [vttest suite homepage](https://invisible-island.net/vttest/)

---

## License

Copyright 2026 Gagik Sargsyan

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
