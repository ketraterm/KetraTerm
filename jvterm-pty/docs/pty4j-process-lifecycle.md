# Pty4j Local Process Lifecycle & Watcher Threads

The `jvterm-pty` module exposes local pseudo-terminal (PTY) processes through the `TerminalConnector` contract, using the JetBrains `pty4j` library as the underlying native engine.

---

## 1. Threading Architecture

To prevent blocking client threads during blocking Native I/O reads or process waiting, [PtyConnector](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-pty/src/main/kotlin/io/github/jvterm/pty/PtyConnector.kt) spawns two background daemon threads upon startup:

```
                  +──────────────────────────+
                  |       PtyConnector       |
                  +────────────┬─────────────+
                               |
         ┌─────────────────────┴─────────────────────┐
         ▼                                           ▼
  [Reader Thread]                            [Watcher Thread]
  ("pty-reader-*")                           ("pty-watcher-*")
         │                                           │
  - Loops on process.inputStream.read()      - Blocks on process.waitFor()
  - Dispatches onBytes(...) serially         - Captures exitCode
  - Reuses flat byte read buffers            - Dispatches onClosed(...)
```

* **Reader Thread (`pty-reader-*`)**:
  * Continually blocks on process `inputStream.read(buffer)`.
  * **Memory optimization**: Reuses a pre-allocated byte buffer (default `4096` bytes) to prevent GC allocation overhead.
  * **Synchronous dispatch**: Immediately forwards read chunks to `TerminalConnectorListener.onBytes(...)`.
* **Watcher Thread (`pty-watcher-*`)**:
  * Blocks on `process.waitFor()` to detect process termination.
  * Captures the integer exit code and safely dispatches `onClosed(exitCode)` to cleanup resources.
* **Daemon Property**: Both threads are explicitly marked as daemon threads (`isDaemon = true`) so that when the main application shuts down, the PTY reader threads do not keep the JVM process alive.

---

## 2. Windows ConPTY Considerations

On Windows systems, `pty4j` abstracts Microsoft's native **Windows Pseudo Console (ConPTY)** APIs:

* **Size updates**: Applications (like `cmd.exe` or `powershell.exe`) require size propagation to compute terminal wrap columns correctly.
* **Synchronous Resize**: The adapter's `resize(cols, rows)` method delegates to `PtyProcess.setWinSize(WinSize(cols, rows))` to ensure the native console host fits the current terminal display width.
