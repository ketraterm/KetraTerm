# KetraTerm Testkit

Repository test support for connector simulation, deterministic terminal replay,
and independent conformance checks. Testkit is used by KetraTerm's own modules;
it is not a published Maven library.

## How to Use in Tests

Add project dependencies to the test source set that needs the helpers. Declare
production APIs that your tests import directly; testkit does not re-export its
implementation dependencies. The connector example below needs transport API:

```kotlin
// In a KetraTerm module's build.gradle.kts.
dependencies {
    testImplementation(project(":ketraterm-testkit"))
    testImplementation(project(":ketraterm-transport-api"))
}
```

### MockConnector

[`MockConnector`](src/main/kotlin/io/github/ketraterm/testkit/MockConnector.kt)
implements the raw-byte connector contract. `feedFromHost` calls the installed
listener synchronously with the supplied array and slice; it does not copy or
parse incoming bytes. The listener must consume or copy that slice before the
callback returns. Outbound writes are copied, and `writtenBytes` returns a fresh
array containing all captured writes.

```kotlin
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnectorListener

fun main() {
    val connector = MockConnector()
    var received = ""
    var remoteExitCode: Int? = null
    connector.start(object : TerminalConnectorListener {
        override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
            received = bytes.decodeToString(offset, offset + length)
        }

        override fun onClosed(exitCode: Int?) {
            remoteExitCode = exitCode
        }

        override fun onError(error: Throwable) = throw error
    })

    connector.feedFromHost("hello".encodeToByteArray())
    val reply = "reply".encodeToByteArray()
    connector.write(reply, 0, reply.size)
    check(received == "hello")
    check(connector.writtenBytes.contentEquals(reply))

    connector.simulateClosed(0)
    check(remoteExitCode == 0)
    connector.close()
    check(connector.startCount == 1 && connector.closeCount == 1)
}
```

`start` accepts one listener and rejects restart or start after local closure.
`close` records each call and prevents subsequent writes and resizes; it does not
emit a remote exit callback. Trigger remote closure or failure explicitly with
`simulateClosed` or `simulateCrash`. These helpers notify the listener without
changing `isClosed`, which records local closure only. `resizeCalls` records
ordered `(columns, rows)` pairs. Serialize access to the fake; it does not add
concurrency control.

### Headless conformance replay

[`TerminalConformanceHarness`](src/main/kotlin/io/github/ketraterm/testkit/TerminalConformanceHarness.kt)
wires the production parser, host adapter, core, response queue, and render reader
without a session, PTY, or UI. A harness retains state between `apply`, `replay`,
and `snapshot` calls. Create a fresh instance for each independent run.

A [`TerminalReplayTranscript`](src/main/kotlin/io/github/ketraterm/testkit/TerminalReplayTranscript.kt)
preserves input chunk boundaries, interleaved resizes, and explicit end-of-input
placement. Input events copy their source bytes. `chunked` and `bytewise` do not
append end-of-input automatically; `TerminalReplayChunkings.exhaustive` does so
by default.

```kotlin
import io.github.ketraterm.testkit.TerminalConformanceDiffer
import io.github.ketraterm.testkit.TerminalConformanceHarness
import io.github.ketraterm.testkit.TerminalReplayChunkings

fun assertChunkingInvariant(bytes: ByteArray) {
    val variants = TerminalReplayChunkings.exhaustive(bytes)
    val expected = TerminalConformanceHarness(80, 24).replay(variants.first().transcript)
    for (variant in variants.drop(1)) {
        val actual = TerminalConformanceHarness(80, 24).replay(variant.transcript)
        val diff = TerminalConformanceDiffer.compare(expected, actual)
        check(diff.isEmpty) { "${variant.name}: ${diff.format()}" }
    }
}
```

Snapshots copy retained history and the live grid, including cell attributes,
clusters, hyperlinks, wraps, cursor, modes, host metadata, and cumulative response
bytes. They omit internal storage handles and generation counters.
`TerminalConformanceDiffer` returns bounded field-level differences.
`exhaustive` covers every two-way split plus selected fragmented partitions; its
quadratic byte copying is appropriate for small protocol fixtures.

## Running Testkit Tests

Run commands from the repository root with JDK 25; use `gradlew.bat` on Windows.

```text
./gradlew :ketraterm-testkit:test
./gradlew :ketraterm-testkit:publishedConsumerTest
./gradlew :ketraterm-testkit:publicationVerificationTest
```

Ordinary `test` covers helper behavior, specification fixtures, model regressions,
and Java compilation against exported project API variants. It skips opt-in
oracle/generated campaigns and excludes the separate publication and retained
client suites. `check` also runs `publishedConsumerTest`; packaging verification
is separate and is included in the root `publicationChecks` aggregate. See the
[published-consumer guide](src/consumerTest/README.md) for prerequisites,
compiler/metadata combinations, and retained client baselines.

### Generated campaigns

| Task | Default cases | Check |
| --- | ---: | --- |
| `xtermDifferentialTest` | 2,000 generated cases plus fixed corpus | Independent headless xterm.js comparison. |
| `xtermDifferentialSmokeTest` | 100 | Generated xterm.js comparison. |
| `xtermDifferentialNightlyTest` | 100,000 | Generated xterm.js comparison. |
| `xtermDifferentialReleaseAudit` | 500,000 | Generated xterm.js comparison. |
| `resizeReflowInvariantSmokeTest` | 100 | Mixed-width text and repeated resize invariants. |
| `resizeReflowInvariantNightlyTest` | 10,000 | Mixed-width text and repeated resize invariants. |
| `cursorWrapModelSmokeTest` | 100 | Independent grid model comparison. |
| `cursorWrapModelNightlyTest` | 25,000 | Independent grid model comparison. |

Invoke these tasks with the `:ketraterm-testkit:` prefix. xterm tasks require
Node.js and npm on `PATH`; they install locked dependencies with
`npm ci --ignore-scripts` and run the oracle's unit tests first. See the
[oracle guide](../tools/xterm-oracle/README.md) for process protocol and setup.
Resize/reflow and grid-model campaigns require no Node.js or native terminal.

Override case counts and deterministic shards with the matching Gradle properties:

| Campaign | Count | First index | Reports under module `build/reports/` |
| --- | --- | --- | --- |
| xterm differential | `xtermDifferentialCases` | `xtermDifferentialStartIndex` | `xterm-differential/` |
| Resize/reflow | `resizeReflowCases` | `resizeReflowStartIndex` | `resize-reflow-invariant/` |
| Grid model | `cursorWrapCases` | `cursorWrapStartIndex` | `cursor-wrap-model/` |

For example:

```text
./gradlew :ketraterm-testkit:cursorWrapModelSmokeTest -PcursorWrapCases=250 -PcursorWrapStartIndex=500
```

Campaign reports retain deterministic replay metadata and manifests. Failures
include reduced reproductions and structural differences. Differential tests
compare the observable intersection with the oracle; intentional disagreements
must name their rationale and exact mismatch paths. KetraTerm's resize/reflow
policy is checked by its own invariants rather than treated as xterm parity.

See [Module.md](Module.md) for helper structure and dependencies.
