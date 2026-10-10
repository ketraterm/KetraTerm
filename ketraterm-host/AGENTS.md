# Terminal Integration Agent Guide

`ketraterm-host` maps parser command-sink calls to public core APIs and host
events. Follow the [root guide](../AGENTS.md) for cross-module ownership.

## Integration boundary

- Keep mapping explicit. Parser owns decoding and parameter recognition; core
  owns bounds, cursor physics, width, storage, and durable modes.
- Convert coordinates only where the contracts differ. Cursor coordinates are
  zero-based in both APIs; scroll margins require conversion; rectangular
  commands preserve DEC coordinates. See the [mapping contract](docs/command-adapter-mapping.md).
- Keep authoritative titles and palette in core. The adapter owns title stacks,
  the bounded hyperlink registry, and host callback delivery.
- Do not add transport, UI, clipboard I/O, worker scheduling, or input encoding
  here. Session and product hosts own execution and lifecycle.
- Do not silently clamp a richer command into a weaker model or manufacture a
  successful result for an unsupported command.

## Serialization and policy

Command calls and mutable registry access are serialized with the supplied
terminal. Synchronous callbacks must not reenter mutation. Preserve callback
ordering and coherent registry indexes when a callback fails.

Policy publication must not mutate registries or emit callbacks from the
publishing thread. Enforce permissions before changing protected metadata,
emitting host actions, or admitting replies. Keep palette permission distinct
from terminal-response permission. Advertised mode and keyboard capabilities
must match the host's implemented actions.

Clipboard admission audits remain content-free. Platform access, consent,
deadlines, and ordered read replies belong outside the adapter. Hyperlink IDs
must never be reassigned during an adapter's lifetime, including after reset,
eviction, or exhaustion.

Intentional mapping gaps belong in the canonical gap map with its existing
ownership markers. Do not reproduce capability inventories or the TODO
taxonomy here.

## Validation

Read the public parser and core contracts for each changed mapping. Prefer
real byte-stream tests using `TerminalParsers`, `HostCommandAdapter`, and public
buffer state. Cover default/denied policy, invalid parameters, callback order,
and relevant mode transitions; vary byte chunk boundaries when dispatch changes.
Unsupported behavior tests must assert the documented outcome rather than a
fabricated degraded result.

Run formatting and `./gradlew :ketraterm-host:test` from the root for behavior
changes.
