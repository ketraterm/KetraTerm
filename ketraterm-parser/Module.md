# Module ketraterm-parser

Converts ordered terminal host bytes into semantic commands. The public entry
points are [io.github.ketraterm.parser.api.TerminalParsers] and
[io.github.ketraterm.parser.api.TerminalOutputParser]; the destination is
[io.github.ketraterm.parser.spi.TerminalCommandSink]. This module depends only on
`ketraterm-protocol`.

## Architectural Role & Pipeline Flow

The parser is synchronous and stateful. Its owner serializes input, reset, and
end-of-input calls; sink callbacks run in order on that same thread. Transport I/O
and session synchronization belong outside this module.

| Component | Responsibility |
|---|---|
| `TerminalParser` | Validates input ranges, coordinates UTF-8/FSM routing, and publishes printable prefixes at call boundaries. |
| `ByteClass` / `AnsiStateMachine` | Classify bytes and select an action plus next state. |
| `ActionEngine` | Collects bounded sequence state, applies parser actions, and flushes text before structural commands. |
| ESC/CSI, SGR, OSC, and DCS dispatchers | Translate completed sequences into semantic sink calls. |
| `PrintableProcessor` / `GraphemeAssembler` | Apply charset mapping, retain grapheme text, and publish initial writes or complete-prefix updates. |
| `ParserState` | Stores reusable parameters, payloads, graphemes, and parser-owned charset context. |

UTF-8 decoding takes priority while a printable scalar is incomplete. Otherwise,
the FSM routes ASCII/control bytes, printable UTF-8 ingress, and opaque string
payloads. Invalid continuation handling can replay the current byte through the
normal route once, preserving a following control sequence.

## Ownership and Resource Contracts

The parser never calculates cell width, clamps coordinates, mutates a grid
directly, or chooses response/clipboard permissions. These decisions remain with
the sink and its owning layers. Effective screen selection is observed through
the sink to keep parser-owned charset save slots aligned with actual screen
transitions.

Primitive fields and reusable arrays support the byte and printable hot paths.
Metadata decoding and permitted large OSC collection can allocate. Array callbacks
borrow storage only for their synchronous duration.

<a id="maintenance"></a>

## Further reading

- [README](README.md): consumer use.
- [ANSI state machine](docs/ansi-fsm-specification.md): byte classification and dispatch.
- [Grapheme segmentation](docs/grapheme-segmentation.md): incremental text assembly.
