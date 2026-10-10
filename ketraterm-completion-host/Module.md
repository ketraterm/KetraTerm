# Module ketraterm-completion-host

Local filesystem support for host-composed completion sources. The package
`io.github.ketraterm.completion.host` contains:

- `TerminalLocalFileSystemProvider`: adapts completion directory requests to a
  local path resolver and scanner.
- `TerminalCompletionPathResolver` and `TerminalLocalFileUriResolver`: lexical
  path and local working-directory URI resolution without filesystem access.
- `TerminalDirectoryScanner` and `TerminalBoundedDirectoryScanner`: the scan
  boundary and its interruptible NIO implementation.
- `TerminalDirectoryEntrySnapshot`: deterministic ordering and prefix projection
  for a bounded entry collection.

The module exposes the completion API and coroutines transitively. It has no
dependency on Swing, IntelliJ, workspace, session, or application modules.
Command parsing, quoting, scheduling, ranking, and request replacement belong
to the completion engine and its host. This module owns no coroutine scope,
background job, or directory-result cache.

See the [README](README.md) for consumer wiring, resolution rules, bounds,
cancellation, and customization.
