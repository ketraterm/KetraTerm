# Module ketraterm-shell-integration

Optional implementation of `TerminalShellIntegrationFactory` for accepted OSC
7/133 metadata. It depends on session contracts; session, PTY, and Swing do not
depend on it.

The producer owns prompt-marker interpretation, stable-line command anchors,
bounded command-text extraction, and primitive command fingerprints. Session
provides serialized frame access and owns shared observation, outbound writes,
and startup submission. The producer owns no threads or coroutine scope.

See [README.md](README.md) for composition and the canonical feature/gap maps
for supported behavior and limits.
