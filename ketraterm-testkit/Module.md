# Module jvterm-testkit

## JvTerm Testkit (`:jvterm-testkit`)

The `jvterm-testkit` module is the dedicated test double and mock harness module for JvTerm Terminal. It provides in-memory connectors and lifecycle simulation tools for testing terminal runtimes, transport layers, and host-bound input/output loops without spinning up physical shells, PTYs, or socket connections.

By decoupling testing from physical operating system interfaces (like OS-level pseudo-terminals or SSH processes), `jvterm-testkit` enables ultra-fast, deterministic, and platform-agnostic testing of terminal components.

---

## Upstream Dependencies
* **`:jvterm-transport-api`** (for standard connector and listener contracts).

---

## Architectural Role

The `MockConnector` serves as a bidirectional bridge in unit and integration tests. It allows tests to feed simulated host responses down to any connector listener while capturing and asserting on the exact bytes written back:

```mermaid
graph TD
    Test["Unit / Integration Test"] -- "1. Feeds bytes / triggers lifecycle" --> MockConnector["MockConnector"]
    MockConnector -- "2. Triggers listener callback" --> Listener["TerminalConnectorListener"]
    Listener -- "3. Writes host-bound bytes" --> MockConnector
    MockConnector -- "4. Captures bytes in memory" --> OutboundBytes["writtenBytes (ByteArray)"]
    Test -- "5. Asserts exact byte sequences" --> OutboundBytes
```

---

## Public API Surface

The module's public surface area contains a single, highly configurable test double:

### [`MockConnector`](./src/main/kotlin/testkit/MockConnector.kt)

#### Lifecycle Tracking Properties
* `startCount: Int`: The number of times `start` was called. Tests can assert this is exactly `1` to verify the connector is not restarted incorrectly.
* `closeCount: Int`: The number of times `close()` was called locally. Excellent for verifying that the local terminal cleanly initiates teardown.
* `isClosed: Boolean`: Indicates whether local close has been requested. Any subsequent calls to `write` or `resize` are ignored after `isClosed` becomes `true`.
* `resizeCalls: List<Pair<Int, Int>>`: An ordered log of columns-to-rows pairs sent via the `resize` function.
* `writtenBytes: ByteArray`: A representation of all bytes written by the system under test to the connector during the test run.

#### Remote Event Simulation APIs
* `feedFromHost(bytes: ByteArray, offset: Int, length: Int)`: Feeds incoming host bytes to the session (triggers `onBytes` on the registered `TerminalConnectorListener`). This mimics raw stdout output from a shell or TUI application.
* `simulateClosed(exitCode: Int? = null)`: Signals to the session listener that the remote process exited with the given exit code.
* `simulateCrash(error: Throwable)`: Signals to the session listener that the transport crashed or failed with an exception.

---

## How to Use in Tests

The following example shows how to write a unit test using `MockConnector` to assert on bidirectional byte flows:

```kotlin
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.testkit.MockConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectorTest {

    @Test
    fun `test raw write and feed simulation`() {
        // 1. Create the MockConnector
        val connector = MockConnector()

        // 2. Wire up a simple listener callback to track incoming bytes
        var receivedString = ""
        connector.start(object : TerminalConnectorListener {
            override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
                receivedString = String(bytes, offset, length, Charsets.UTF_8)
            }
            override fun onClosed(exitCode: Int?) {}
            override fun onError(error: Throwable) {}
        })

        // 3. Simulate host emitting data (stdin/stdout write)
        val hostData = "Hello from Host".toByteArray(Charsets.UTF_8)
        connector.feedFromHost(hostData, 0, hostData.size)

        // 4. Assert the listener received the fed bytes
        assertEquals("Hello from Host", receivedString)

        // 5. Test client write to the connector
        val clientData = "Client Request".toByteArray(Charsets.UTF_8)
        connector.write(clientData, 0, clientData.size)

        // 6. Assert the mock connector captured the client's output
        val captured = String(connector.writtenBytes, Charsets.UTF_8)
        assertEquals("Client Request", captured)
    }
}
```

---

## Testing best practices

1. **Assert Real Semantics**: In-memory mocks do not fake intermediate or half-finished behaviors. They provide raw, byte-level capture so that tests assert *real* wire protocols rather than mock method signals.
2. **Explicit Captured Bytes**: The mock connector does not convert or interpret bytes itself. It acts as a passive sink and leaves the interpretation of bytes to assertions, ensuring tests remain explicit and readable.
3. **Remote Events Must Be Explicit**: Local `close()` only records that the local application requested a shutdown. Remote exit/crashes must always be triggered via explicit simulation functions (`simulateClosed` / `simulateCrash`) rather than assuming remote side-effects.

---

## Running Testkit Tests

To run the checks for this module:
```bash
./gradlew :jvterm-testkit:test
```
