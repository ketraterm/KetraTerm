# Module ketraterm-input

Host-bound encoding for platform-neutral input events. The public facade reads
core input modes and writes through `TerminalHostOutput`; the module neither
parses terminal output nor owns a connector.

<a id="packages"></a>

## Source layout

| Package | Responsibility |
| --- | --- |
| `io.github.ketraterm.input` | Public encoder factory and owned clipboard-reply preparation. |
| `io.github.ketraterm.input.api` | Encoder interface and custom encoder binding contract. |
| `io.github.ketraterm.input.event` | Immutable events, key/button vocabulary, and modifier validation. |
| `io.github.ketraterm.input.policy` | Keyboard fallbacks, paste transformations, and bounded mouse-coordinate policy. |
| `io.github.ketraterm.input.impl` | Internal event encoders and reusable output buffers. |
| `io.github.ketraterm.input.impl.keyboard` | Legacy xterm/DEC and Kitty keyboard encoding and key mapping tables. |

## Dependencies

The module exports its `ketraterm-core` and `ketraterm-protocol` dependencies.
Core supplies coherent input-mode reads; protocol supplies wire vocabulary and
the synchronous byte-sink contract. Session supplies outbound admission,
serialization, and transport lifetime.

See the [README](README.md) for construction and the
[input contract](docs/terminal-input-contract.md) for observable behavior.

Input tests assert exact bytes, event validation, real core mode changes, scratch
reuse, and write-failure recovery. Run `./gradlew :ketraterm-input:test` for this module's tests.
