# Module ketraterm-protocol

Shared protocol vocabulary. Its only production dependency is the Kotlin
standard library. The module contains identifiers, semantic values, small validation helpers, and a
host-bound byte sink; parsing and terminal behavior remain in their owning layers.

<a id="packages"></a>

## Source layout

| Package | Responsibility |
| :--- | :--- |
| `io.github.ketraterm.protocol` | Control bytes, modes, identity, clipboard selectors, and host metadata. |
| `io.github.ketraterm.protocol.keyboard` | xterm key resources and Kitty keyboard values. |
| `io.github.ketraterm.protocol.mouse` | Normalized integer mouse modes for packed input state. |
| `io.github.ketraterm.protocol.host` | `TerminalHostOutput` synchronous byte-consumption contract. |

The root-package mouse enums describe selections; the `mouse` package constants
represent their normalized values. Neither representation uses DEC mode numbers.
Core input-state packing relies on the correspondence between enum ordinals
and normalized values.

See the [README](README.md) for integration, [mode reference](docs/protocol-modes.md)
for mode values, and [keyboard reference](docs/input-protocols.md) for resource and
flag definitions. Published API KDoc describes individual constants and helpers.
