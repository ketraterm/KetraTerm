# Module ketraterm-shell-integration

Optional OSC-based producer for the shell metadata contracts in
`ketraterm-session`. The public package
`io.github.ketraterm.shell.integration` exposes `OscShellIntegration` and its
configuration factory. Session, PTY, and Swing do not depend on this module.

## Dependencies

The module exports `ketraterm-session` for shell metadata and frame-access
contracts. `ketraterm-protocol` is an implementation dependency.

## Components

The producer owns prompt-marker interpretation, stable-line command anchors,
bounded command-text extraction, and primitive command fingerprints. Session
provides serialized frame access and owns shared observation, outbound writes,
and startup submission. The producer owns no threads or coroutine scope.

`ShellIntegrationCommandTextExtractor` is internal. It consumes primitive render
data, preserves hard breaks and soft wraps, and fails closed when the required
range or cluster data cannot be reconstructed. Marker callbacks capture command
metadata; subscribed edit tracking compares fingerprints before requesting
allocating snapshots.

The test suite covers real OSC byte streams, marker ordering, clear and resize,
wrapped and Unicode command extraction, subscription lifetime, and startup
submission.

See [README.md](README.md) for consumer composition and
[session contracts](../ketraterm-session/docs/session-concurrency-locks.md#selected-shell-integration)
for synchronization and model ownership. Capability scope remains in the
[feature map](../docs/terminal-feature-map.md) and
[gap map](../docs/terminal-feature-gap-map.md).
