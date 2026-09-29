# Module ketraterm-session

The runtime synchronization boundary between transport, parser, core, input
encoding, and render publication. Session owns ordered outbound writes, render
publication, lifecycle cancellation, and observation of a selected neutral shell
model. It does not depend on the optional OSC shell integration implementation.

See [README.md](README.md) for composition and host-owned shell integration,
[session-concurrency-locks.md](docs/session-concurrency-locks.md) for ordering and
ownership, and [asynchronous-render-coalescing.md](docs/asynchronous-render-coalescing.md)
for publication scheduling. Capability status belongs in the canonical feature
and gap maps.
