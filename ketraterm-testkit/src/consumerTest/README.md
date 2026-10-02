# Published library consumers

Run from the repository root with JDK 25:

```text
./gradlew :ketraterm-testkit:publishedConsumerTest
```

This verification is also part of `:ketraterm-testkit:check` and the test CI
matrix. Each external project declares just one KetraTerm dependency: parser,
host, completion, ui-swing or pty. Kotlin and Java sources compile and execute
public APIs; the smoke programs verify parsed/grid text, collected completion
results, Swing host integration and packaged Kotlin metadata.

The Swing consumer creates and binds a public `SwingTerminal` on the EDT to a
host-owned session and in-memory connector. A suspending detector supplies neutral
presentation and activation metadata. Public hover and activation callbacks verify
that discovery results were installed, including a provider configuration refresh
without terminal output. Unbinding cancels the view's provider subscription;
unbinding and disposal leave session/transport ownership with the host. Callback
handshakes coordinate the headless smoke; it uses no timing assertions, private
reflection, internal constructors or extra library dependencies. The Java smoke
uses functional actions and public host wiring, without adapting coroutines.

The PTY consumer supplies an in-memory implementation of the exported pty4j
process contract. It verifies Java construction, Kotlin constructor defaults,
resize forwarding and connector-owned destruction without launching a native
process or starting reader threads. It declares no separate pty4j dependency.

The root build stages the existing Maven publications' generated POM/module
metadata and main jars into `build/library-consumer-repository`. It includes
their transitive KetraTerm artifacts. Release signing,
remote publishing, Maven Local and source-project substitution are not used.
Sources and documentation variants are outside this runtime-consumer check.

The fixture is copied into testkit's build directory and run twice, with
separate compiler outputs: Gradle module metadata, then POM-only resolution
with Gradle metadata redirection disabled. KetraTerm coordinates resolve
exclusively from the staged repository; third-party dependencies resolve from
Maven Central. The repository's Gradle wrapper, Kotlin plugin version and JDK
25 toolchain are reused.

Existing `TerminalLibraryConsumerCompilationTest` regressions separately check
Java compilation against exported project API variants without the fixture's
Kotlin plugin or testkit classpath.

## Compiled-client upgrades

```text
./gradlew :ketraterm-testkit:compiledClientUpgradeTest
```

This check is included in `publishedConsumerTest`. It runs the five tracked
`baseline/*.jar` clients against the current staged runtime publications in
both metadata modes, using Maven stdlib 2.4.20 and 2.4.0, the latter matching
IDEA 2026.2's boot runtime version (20 executions). This does not run the IDE's
packaged `util-8.jar`. Each classpath must resolve exactly the expected
stdlib version. An isolated Java launcher checks the actually loaded Kotlin
version and its code-source jar before invoking the retained main; no testkit
classes or current fixture outputs enter the child classpath. Runtime resolution
never depends on fixture compilation:
the old Kotlin and Java classes are not rebuilt or combined with old library
classes. SHA-256 provenance verifies the retained bytes before each execution.

The clients exercise actual parsing/grid text, Kotlin default calls and data
class `copy`, Java overloads, an external connector implementation, interface
default callbacks, an inline publisher read, completion Flow, host-owned shell
metadata and clipboard reply bytes. Mode-bit checks compare inlined baseline
values with current public fields and their observable semantics. Controls remove
the parser artifact and shadow its factory with a class missing the old method;
they require `NoClassDefFoundError` and `NoSuchMethodError`, respectively. Process deadlines
bound hangs; no timing-based assertions are used.

Only an intentional compatibility-boundary review should replace these clients:

```text
./gradlew :ketraterm-testkit:recordCompiledClientBaseline
```

That task recompiles the checked-in sources against the current real Maven
publications and writes deterministic client jars and provenance into
`src/consumerTest/baseline`. Provenance identifies a reviewed working-tree basis
and its base revision, source and resolved dependency hashes, Kotlin compiler
version and JDK target/toolchain.
Use `-PcompiledClientBaselineRevision=<base-revision>` to override the default
HEAD base. Source and artifact hashes identify the actual recorded inputs,
including reviewed uncommitted changes. Review the source, provenance,
and ABI differences together; run `spotlessApply`, `publishedConsumerTest` and
the repository's ABI check before accepting an intentional update. Ordinary
verification must never re-record this baseline to hide an upgrade failure.
The source hashes identify recording inputs; Git history retains the original
fixture sources when current source-consumer checks evolve independently.

The small jars retain compiled Kotlin/Java callers, including public inline
bodies; library classes resolve from the current publications. They cover
representative executed boundaries; the tracked ABI checks cover the
remaining reviewed declarations. This initial pre-v1 baseline establishes the
upgrade mechanism and does not claim compatibility with earlier releases.
