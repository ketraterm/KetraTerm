# KetraTerm Transport API (`:ketraterm-transport-api`)

The connector boundary between a terminal session and a host byte stream. This
module defines lifecycle callbacks, outbound writes, terminal resize requests,
and optional foreground-process metadata. It has no dependencies on other
KetraTerm modules.

## Architectural Role & Core Interfaces

| API | Responsibility |
| --- | --- |
| [`TerminalConnector`](src/main/kotlin/io/github/ketraterm/transport/TerminalConnector.kt) | Start delivery, write bytes, resize, query optional metadata, and close local resources. |
| [`TerminalConnectorListener`](src/main/kotlin/io/github/ketraterm/transport/TerminalConnectorListener.kt) | Consume ordered host bytes and receive remote closure or failure. |
| [`ByteArray.checkBounds`](src/main/kotlin/io/github/ketraterm/transport/Validation.kt) | Validate an offset/length range without overflowing `offset + length`. |

Connectors own transport resources and any I/O workers. A
[`TerminalSession`](../ketraterm-session/README.md) owns parsing, terminal state,
and the order of outbound terminal bytes. Consumers normally give their connector
to a session rather than attaching a separate listener. For a local process, use
the [PTY module](../ketraterm-pty/README.md).

## How to Use

For a raw stream consumer, attach one listener and process each byte range before
returning. This example forwards callbacks without retaining borrowed arrays:

```kotlin
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener

fun startRawStream(
    connector: TerminalConnector,
    consumeBytes: (ByteArray, Int, Int) -> Unit,
    onRemoteClose: (Int?) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    connector.start(object : TerminalConnectorListener {
        override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
            consumeBytes(bytes, offset, length)
        }

        override fun onClosed(exitCode: Int?) {
            onRemoteClose(exitCode)
        }

        override fun onError(error: Throwable) {
            onFailure(error)
        }
    })
}
```

The caller owns shutdown and should close the connector when finished, including
after startup failure. Callbacks may run on transport workers; this example does
not dispatch them to a UI thread. `consumeBytes` must finish using the supplied
range before it returns. Copy the range if asynchronous processing is necessary.
Chunk boundaries can split UTF-8 characters or escape sequences, so use a
streaming parser rather than decoding each callback independently.

`connector.write(bytes)` writes the whole array; `connector.write(bytes,
offset = n)` writes the remaining suffix. The connector must consume or copy the
range before returning. This lifetime guarantee does not make I/O nonblocking or
prove that the remote host has processed the bytes.

## How to Implement: Custom Transport Connector

Read the [lifecycle and thread contract](docs/connector-lifecycle.md) before
implementing a connector. In particular:

- Accept at most one start attempt. Reject restart and startup after local close
  with `IllegalStateException`.
- Deliver `onBytes` serially, in stream order. Finish final byte callbacks before
  reporting remote closure.
- Stop byte delivery after a byte-consumer failure and report its original cause
  through `onError`, unless local shutdown suppresses it. Never retry the range.
- Support idempotent local close, including close from lifecycle callbacks.
- Document transport-specific blocking, resize, and failure behavior. The
  interface does not provide a universal timeout or concurrent-write policy.

The [PTY implementation](../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyConnector.kt)
shows process-specific lifecycle handling. Its worker and shutdown choices are
implementation details rather than requirements for every connector.

## Sub-Documentation

- [Connector lifecycle and thread invariants](docs/connector-lifecycle.md).
- [Module ownership](Module.md).
