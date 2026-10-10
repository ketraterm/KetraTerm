# Published library consumers

These fixtures verify the supported libraries as an external application would:
from staged Maven artifacts, through exported dependencies and public APIs.
They are a separate Gradle build copied into testkit's `build/published-consumers`
directory; source-project substitution and testkit's classpath are not used.

Run from the repository root with a full JDK 25 installation or configured JDK 25
toolchain. The repository wrapper is reused; use `gradlew.bat` on Windows.
Dependency resolution needs access to the configured Gradle/Maven repositories
unless those artifacts are cached. These checks need no Node.js, native PTY,
window system, or IDE installation: Swing runs headlessly and PTY callers use an
in-memory pty4j process implementation.

```text
./gradlew :ketraterm-testkit:publishedConsumerTest
```

`publishedConsumerTest` is part of `:ketraterm-testkit:check` and the root
`publicationChecks` aggregate. It includes both source-consumer verification and
[compiled-client upgrades](#compiled-client-upgrades).

## Source-consumer verification

Eleven fixture projects reach the 15 supported JVM libraries and the headless and
Swing dependency entry points through one declared KetraTerm dependency per
project. The fixture must not repair a missing export by declaring additional
library dependencies. Kotlin and Java callers compile and run in four combinations:

| Compiler | Resolution metadata |
| --- | --- |
| Current root Kotlin plugin version | Gradle module metadata |
| Current root Kotlin plugin version | POM only |
| Kotlin 2.4.0 | Gradle module metadata |
| Kotlin 2.4.0 | POM only |

POM-only cases disable Gradle metadata redirection. Outputs are separated by
compiler and metadata mode. KetraTerm coordinates resolve exclusively from
`build/library-publication-repository`; third-party libraries resolve from Maven
Central. The parent build stages the actual Maven publications, including
snapshot metadata, before invoking the fixture. These tasks do not upload to a
remote repository or use Maven Local. Release-mode staging requires signing,
just as release delivery does.

Verification covers:

- Parsed text, core reads, external parser sinks, host observers, render readers,
  completion results, shell metadata, and host composition.
- Swing construction and binding on the EDT, detector discovery and refresh,
  provider cancellation on unbind, and host ownership of session/transport.
- Product source labels and search presentation through Kotlin and Java APIs;
  provider and suggestion configuration across construction and unbinding.
- Exported pty4j types, constructor defaults, resize forwarding, and connector
  destruction without starting a native process.
- BOM version alignment and an empty BOM-only runtime graph; entry-point graphs
  and absence of unwanted optional/product dependencies.

`extractSwingReadmeExample` extracts the uniquely marked Kotlin fence in
[ketraterm-ui-swing/README.md](../../../ketraterm-ui-swing/README.md) into the staged
Swing consumer. Missing or duplicate markers fail extraction; API drift fails
compilation. Its smoke runs on the EDT, verifies off-EDT rejection, exercises
both documented construction orders, disposes the views, and checks that their
host-owned session remains open.

`verifyPublicationBoundary` rejects product, benchmark, and testkit publications
at the requested version and mixed library versions in runtime graphs. Parser
and core consumers must stay free of UI/native hosting. The base Swing consumer
must stay free of optional PTY, OSC, workspace, and completion implementations.
Workspace and completion persistence are product modules outside the supported
Maven boundary; their owner-module tests verify their behavior.

`TerminalLibraryConsumerCompilationTest`, run by ordinary testkit `test`,
separately invokes Java compilation against exported project API variants.
It does not replace the artifact-based source-consumer checks above.

## Packaging verification

```text
./gradlew :ketraterm-testkit:publicationVerificationTest
```

This separate task checks the staged repository's artifacts, metadata, packaged
sources and API documentation, checksums, and required signatures. It is included
in root `publicationChecks`, rather than testkit's ordinary `test` task or
`publishedConsumerTest`.

## Compiled-client upgrades

```text
./gradlew :ketraterm-testkit:compiledClientUpgradeTest
```

Eleven tracked `baseline/*.jar` clients run against the current staged runtime
publications in both metadata modes and with the current Kotlin runtime and
Kotlin 2.4.0. This produces 44 successful-client cases. Two additional controls
require `NoClassDefFoundError` when the parser artifact is removed and
`NoSuchMethodError` when a used factory method is absent.

Runtime resolution does not compile client sources. Child JVMs load the retained
client jars plus current library publications, without testkit classes or current
fixture outputs. Provenance validates each jar's SHA-256. A Java launcher checks
the loaded Kotlin version and its code-source jar before invoking the retained
main; process deadlines bound hangs. These are Maven stdlib cases, not execution
inside an IDE's packaged runtime.

Retained callers exercise Kotlin defaults and data-class calls, Java overloads,
external implementations and inherited interface defaults, mode-bit values,
inline render leases, callback failure and buffer recycling, completion Flow,
clipboard replies, and optional host composition. They cover representative
executed boundaries; ABI snapshots check declarations separately. Passing these
cases does not establish compatibility with every earlier 0.x release.

### Updating a retained baseline

Use this task only after an intentional compatibility decision:

```text
./gradlew :ketraterm-testkit:recordCompiledClientBaseline
```

It compiles the checked-in fixture sources against current staged publications
and replaces deterministic client jars and provenance in
`src/consumerTest/baseline`. Provenance records the working-tree basis, base
revision, source and dependency hashes, compiler, and JDK target/toolchain.
`-PcompiledClientBaselineRevision=<base-revision>` overrides the default HEAD
basis; hashes still identify the actual inputs, including reviewed uncommitted
changes.

Review client sources, jars, provenance, and ABI changes together. Run formatting,
`publishedConsumerTest`, and the owning modules' `checkKotlinAbi` tasks before
accepting a replacement. Ordinary verification must never re-record clients to
hide an upgrade failure. Current source fixtures may evolve independently of
retained binary inputs.

The baseline has intentionally changed during development construction,
configuration, reader-ownership, and suggestion-lifecycle migrations. Their
review decisions and compatibility limits belong in the
[compatibility guide](../../../docs/library/compatibility.md#verification-and-baseline-changes),
[configuration guide](../../../docs/library/configuration.md), and
[reader-ownership guide](../../../docs/library/render-ownership.md). The retained
jars and provenance define the current baseline; historical review counts do not.
