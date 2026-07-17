# KetraTerm Terminal

**KetraTerm**  is a next-generation, high-performance, strictly modular terminal emulator library written in **Kotlin/JVM 21**.

Designed for embedding into IDEs, developer tools, and standalone desktop applications, KetraTerm provides a clean, fast, and modern terminal architecture. It rejects the bloated legacy compatibility of the 1980s (like printer passthroughs or Tektronix vector graphics) to focus on contemporary shells and text-user interfaces (TUIs).


---

## Features

* **Native Pseudo-Terminal (PTY) Integration**: Seamless cross-platform native execution using JetBrains [Pty4J](https://github.com/traff/pty4j) with full Windows ConPTY support, built to handle modern shells (Zsh, Fish, PowerShell) and prompt size propagation.
* **Modern TUI & vt100/xterm Compliance**: Passes most tests of the rigorous `vttest` suite, ensuring flawless rendering for heavy interactive TUI applications like Neovim, Tmux, Htop, Fzf, and lazygit.
* **Richer Styling & 24-Bit TrueColor**: Bypasses the limits of standard 256-color palettes with full 24-bit TrueColor RGB mapping. Renders overline decorations and modern underline styles (Single, Double, Curly, Dotted, Dashed) with custom underline colors.
* **Advanced Keyboard Shortcuts**: Supports DEC Backarrow mode, conventional Ctrl-number controls, xterm modified and extended function keys, `modifyOtherKeys`, compact CSI-u, and Kitty keyboard progressive flags `1` (escape-code disambiguation) and `8` (report all keys as CSI-u). Swing preserves press/repeat/release for AWT-visible non-text physical keys without per-event allocation. Rich native layout, IME, alternate-key, and associated-text reporting (`2`, `4`, `16`) is explicitly deferred and never advertised by portable hosts.
* **Unbounded Mouse Tracking**: Supports legacy mouse tracking alongside modern Standard SGR Mouse (`1006`), SGR-Pixels (`1016`), and URXVT (`1015`) decimal-packed coordinates, allowing precise clicks, drags, and scroll-wheel interactions beyond the legacy 223-cell limit.
* **Tear-Free Triple-Buffered Rendering**: Decouples active parsing from the UI drawing loops using an asynchronous dirty-coalescing rendering worker and a triple-buffered publisher. Delivers a clean **60+ FPS** paint cycle with zero visual tearing or stuttering under massive log outputs.
* **Security-Hardened Design**: Hardened against malicious escape sequence exploits. Utilizes a bounded, double-indexed LRU cache for OSC 8 hyperlinks, strict xterm title stack limits, and pre-allocated OSC/DCS payload buffers to block memory exhaustion.
* **Pixel-Perfect Typography & Color Emojis**: Integrates UAX #29 grapheme cluster segmentation, East Asian width policies, custom fallback font chains, and prioritized OS color emojis (Apple, Segoe, Noto). Programmatically paints box-drawing and block characters to eliminate anti-aliased line gaps.
* **Zero-Allocation Memory Profile**: Core grid storage is built on flat parallel primitive arrays (no object-per-cell overhead) and a circular arena allocator (`ClusterStore`), ensuring near-zero garbage collector pressure and pauses during active shell throughput.
* **Independent Buffer & Margin Physics**: Employs vertical and horizontal scroll margins (`DECSLRM`/`DECSTBM`) with instant switching between primary and alt buffers (`?1049`) carrying independent margins and cursor state save slots.
* **VT420 Rectangular Operations**: Supports protected, wide-glyph-safe rectangular erase, fill, copy, attribute updates, column edits, and active-page checksum responses for demanding text TUIs.
* **Native Desktop Notifications**: Fully supports native desktop notifications triggered directly via iTerm2-style `OSC 9` and urxvt-style `OSC 777` sequences, featuring a KetraTerm-specific severity extension (`info`, `warning`, `error`, `none`), ConEmu subcommand conflict filtering, and self-cleaning tray icon management.
* **Exceptional Utf-8 Support**: Supports UTF-8 input and output, with Unicode 17.0.0 data-backed grapheme segmentation, emoji properties, and East Asian width policies for modern emojis, symbols, and scripts.
* **Modern Shell Integration**: Implements modern shell integration protocols (`OSC 133`, `OSC 7`) for accurate prompt detection, command lifecycle tracking, exit status reporting, and current working directory synchronization.

> For a complete specification of all supported capabilities, see the [Terminal Feature Map](docs/terminal-feature-map.md). A detailed list of current backlog items and compatibility decisions is maintained in the [Terminal Feature Gap Map](docs/terminal-feature-gap-map.md)

> Deterministic differential, resize/reflow, and independent grid-model verification are documented in [Terminal Conformance Testing](docs/terminal-conformance-testing.md).

---


## Seamless Integration Guide

Integrating a local shell into a Swing application requires only a few lines of configuration:

```kotlin
import io.github.ketraterm.pty.TerminalSessions
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
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
    val frame = JFrame("KetraTerm Terminal")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.layout = BorderLayout()
    frame.add(terminalComponent, BorderLayout.CENTER)
    frame.pack()
    frame.isVisible = true
}
```

---

## Project Structure

KetraTerm is composed of strict, decoupled Gradle modules:

* **`:ketraterm-protocol`**: Zero-dependency ANSI/DEC constants and vocabulary enums.
* **`:ketraterm-parser`**: Streaming UTF-8 decoder and table-driven escape sequence FSM.
* **`:ketraterm-core`**: Headless text grid storage, circular scrollback buffers, and resizing reflow.
* **`:ketraterm-host`**: Semantic translation adapter connecting the parser to core state.
* **`:ketraterm-input`**: Keyboard/mouse event models and host-bound ANSI encoders.
* **`:ketraterm-completion`**: Dependency-free command completion models, parsing, ranking, and learning indexes.
* **`:ketraterm-completion-host`**: Bounded asynchronous snapshot and local path-provider infrastructure.
* **`:ketraterm-completion-persistence`**: Optional sanitized local-file storage for completion learning.
* **`:ketraterm-render-api`**: Dependency-free visual frame contracts.
* **`:ketraterm-render-cache`**: Double/triple-buffered publication cache.
* **`:ketraterm-transport-api`**: Duplex I/O connector interfaces.
* **`:ketraterm-session`**: Thread synchronization, lock controls, and event loop.
* **`:ketraterm-pty`**: Local native process Pty4J launcher and stream pump.
* **`:ketraterm-ui-swing`**: Reusable desktop `JComponent` painter and mouse interaction adapters.
* **`:ketraterm-ui-swing-host`**: Optional host chrome, actions, and completion-to-Swing adapters.
* **`:ketraterm-workspace`**: Headless tab/profile workspace layer used by product hosts.
* **`:ketraterm-app`**: Standalone desktop application host.
* **`:ketraterm-testkit`**: In-memory connector mocks and simulation tools.
* **`:ketraterm-benchmarks`**: JMH benchmarks for parser, core, render, and session hot paths.

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
  ./gradlew :ketraterm-parser:test
  ./gradlew :ketraterm-core:test
  ./gradlew :ketraterm-ui-swing:test
  ```
* **Run Generated Terminal Campaigns**:
  ```bash
  ./gradlew :ketraterm-testkit:xtermDifferentialSmokeTest
  ./gradlew :ketraterm-testkit:resizeReflowInvariantSmokeTest
  ./gradlew :ketraterm-testkit:cursorWrapModelSmokeTest
  ```
* **Launch Standalone Swing App**:
  ```bash
  ./gradlew :ketraterm-app:run
  ```
* **Launch with Custom Shell Command**:
  ```bash
  ./gradlew :ketraterm-app:run --args="powershell.exe"
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
