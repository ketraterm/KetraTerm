# Module ketraterm-host

Parser-to-core command mapping and host-control policy for the terminal pipeline.
The public package is `io.github.ketraterm.host`.

## Dependencies

The module exports `ketraterm-parser` for semantic command contracts,
`ketraterm-core` for state mutation, and `ketraterm-protocol` for shared values.

## Integration boundary

`HostCommandAdapter` implements the parser's command sink using public core APIs.
It owns coordinate conversion where the two APIs differ, title stacks, the
bounded OSC 8 registry, and permission checks before host effects or query
replies are admitted. Current titles and the effective palette remain core-owned.

`HostEventSink` is the host-action boundary. Its synchronous parser callbacks do
not confer permission to reenter mutation or block on UI/platform work. The
adapter owns neither transport lifecycle nor output scheduling; session owns
those responsibilities in an assembled pipeline.

## Contracts and navigation

- [README](README.md) provides direct construction, threading, and policy guidance.
- [Command mapping](docs/command-adapter-mapping.md) documents mixed coordinate
  conventions and reset/response boundaries.
- [Hyperlink registry](docs/hyperlink-registry.md) defines retention and identity lifetime.
- [Core contract](../ketraterm-core/docs/terminal-core-contract.md) defines grid semantics.
- The [feature map](../docs/terminal-feature-map.md) and
  [gap map](../docs/terminal-feature-gap-map.md) own protocol support status.
