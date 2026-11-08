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
bounded command-text extraction, immutable styled prompt-row capture, and primitive command fingerprints. Session
provides serialized frame access and owns shared observation, outbound writes,
and startup submission. The producer owns no threads or coroutine scope.

`NativeCompletionProtocol` validates the bounded private OSC 633 metadata
and publishes prompt readiness/results through the session-owned completion
channel. It maps session-assigned request IDs to semantic request frames. Session
owns ID allocation, admission, request lifetime, and edit validation;
workspace owns the installed PowerShell script. The producer performs no shell I/O.

`ShellCommandInventoryProtocol` assembles independent ordered, bounded shell-local and PATH snapshots,
then merges them with shell-local precedence
and validates its totals before publication. It preserves completed authority
during same-environment refresh, expires partial state on output activity, and
releases buffers on session closure. Wire limits and failure semantics live in
the [protocol reference](../docs/reference/protocol.md#private-bash-command-inventory).

`ShellCommandInventoryInput` provides independent ingestion of completed PATH
snapshots. It validates the complete envelope, checks the live-shell epoch, and
uses the same decoder under session serialization without touching the terminal
parser or grid. The launching host owns file access and observation; closure
detaches the input. This keeps background inventory output out of the PTY.

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
