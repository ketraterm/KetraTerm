# KetraTerm Protocol (`:ketraterm-protocol`)

Shared terminal vocabulary for parser, core, host, input, and integration modules.
Use this artifact when an adapter needs the same wire identifiers or semantic
values as the terminal pipeline.

<a id="architectural-role"></a>

## Protocol vocabulary

Protocol definitions identify a command or value; the owning layer decides how to
parse, apply, encode, or authorize it. A constant's presence does not establish
end-to-end support. See the [feature map](../docs/terminal-feature-map.md) for
supported behavior.

### What the Module Owns

| Vocabulary | Use |
| :--- | :--- |
| `ControlCode`, `AnsiMode`, `DecPrivateMode` | Control bytes and mode parameters. |
| `TerminalModeStatus`, `TerminalHostModeCapability` | Mode query status values and host action capability bits. |
| Mouse enums and `protocol.mouse` constants | Semantic mode selections and their normalized integer representation. |
| `protocol.keyboard` constants | xterm resources, Kitty flags, event types, and functional key codes. |
| `TerminalCapabilityIdentity` | Shared environment and query identity values. |
| `TerminalClipboardSelection` | Validated OSC 52 selectors, deduplicated in request order. |
| `ShellIntegrationEvent`, `NotificationLevel` | Host-facing metadata vocabulary. |
| `TerminalHostOutput` | Synchronous host-bound byte sink used by input encoders. |

`TerminalClipboardSelection.parse("")` selects `c`; unknown selectors return
`null`. Validation of a selector does not grant clipboard access.

## Sub-Documentation

- [Mode identifiers](docs/protocol-modes.md): ANSI/DEC modes and status vocabulary.
- [Keyboard vocabulary](docs/input-protocols.md): xterm resources and Kitty values.
- [Module context](Module.md): package ownership and representation constraints.

## How to Use

Depend on `io.github.ketraterm:ketraterm-protocol`, using the version alignment
shown in the [root README](../README.md#using-the-libraries). Import constants from
`io.github.ketraterm.protocol` and keyboard vocabulary from
`io.github.ketraterm.protocol.keyboard`.

`ControlCode` values are unsigned integers; compare a Kotlin byte using
`byte.toInt() and 0xff`. Mouse mode enums and packed-state constants use normalized
values, distinct from DEC wire parameters.

## How to Extend: Custom Transport Sinks

Implement `TerminalHostOutput` when adapting an encoder to an existing byte
consumer. Every call is synchronous: consume or copy a supplied array range
before returning, because the caller may reuse it immediately. Callers own
serialization; the interface does not guarantee ordering between concurrent
producers. `TerminalSession` supplies that ordering in the standard pipeline.
