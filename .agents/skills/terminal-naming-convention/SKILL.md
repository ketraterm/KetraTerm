---
name: terminal-naming-convention
description: Naming conventions and file layout policy for JVM Terminal (JVTerm) codebase. Use when creating new classes, renaming files, adding public APIs, application components, or internal adapters.
---

# JVM Terminal Naming Conventions

This guide defines the class and file naming conventions to preserve both public API clarity and clean internal module structures.

## 1. Public Cross-Module APIs
Public classes and interfaces that represent primary cross-module abstractions must remain explicit and recognizable. Use the `Terminal*` domain prefix to prevent name collisions (e.g., with standard JDK IO classes like `Reader` and `Writer`) and ensure clarity in user imports, documentation, and IDE autocompletion.
- *Examples*: `TerminalBuffer`, `TerminalSession`, `TerminalWorkspace`, `TerminalReader`, `TerminalWriter`, `TerminalResponseChannel`, `TerminalInputEncoder`, `TerminalInputPolicy`.
- *Reasoning*: A generic class name like `Session` or `Writer` is too ambiguous when imported into an external codebase.

---

## 2. Module-Specific Public APIs (Distinguishing Prefixes)
When a module has a strong distinguishing prefix, use it instead of the generic `Terminal` prefix to avoid verbosity and redundant stuttering.
- **Swing UI (`jvterm-ui-swing`)**: Use the `Swing*` prefix.
  - *Yes*: `SwingTerminal`, `SwingHostServices`, `SwingSettings`, `SwingMetrics`.
  - *No*: `SwingTerminal`, `SwingSettings`, `SwingMetrics`.
- **PTY Processes (`jvterm-pty`)**: Use the `Pty*` prefix.
  - *Yes*: `PtyOptions`, `PtyEventListener`, `PtyConnector`, `PtyConnectors`.
  - *No*: `PtyOptions`, `PtyEventListener`, `TerminalSessions` (as a PTY factory).
  - *Note*: High-level public session creation should go through `TerminalSessions.localPty(...)` to provide a single, unified factory entry-point rather than exposing local PTY factories.
- **Cell Styling (`jvterm-core`)**: Use the `Cell*` prefix.
  - *Yes*: `CellAttributes`, `CellColor`, `CellColorKind`.
  - *No*: `TerminalAttributes`, `TerminalColor`, `TerminalColorKind`.

---

## 3. Host Bridge Layer
Use the `Host*` prefix for parser-to-core host metadata and policy APIs to keep the mapping interface clear.
- *Yes*: `HostCommandAdapter`, `HostEventSink`, `HostPolicy`.
- *No*: `TerminalHostEventSink`, `TerminalHostPolicy`.

---

## 4. Product/Application Layer
Use the `JvTerm*` prefix only for runnable applications, shell wrappers, or look-and-feel classes. Do not use it for library-level models or parsers.
- *Yes*: `JvTermApp`, `JvTermLookAndFeel`, `JvTermLauncher`.
- *No*: `JvTermBuffer`, `JvTermParser`, `JvTermSession`.

---

## 5. Internal Implementations
Internal classes, private managers, and helper components should avoid the redundant `Terminal` prefix. They should rely on standard implementation conventions:
- Use the `Impl` suffix or a context descriptor for interface implementations:
  - *Yes*: `WriterImpl` or `BufferTerminalWriter`, `InspectorImpl` or `BufferInspector`.
  - *No*: `BufferWriter`, `BufferInspector`.
- Use the `Default` prefix for default implementations of input or transport contracts:
  - *Yes*: `DefaultInputEncoder`.
  - *No*: `DefaultTerminalInputEncoder`.
- Use domain-descriptive internal names:
  - *Examples*: `SessionHostEventBridge`, `FixedInputState`.
