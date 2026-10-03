# Published library consumers

Run from the repository root with JDK 25:

```text
./gradlew :ketraterm-testkit:publishedConsumerTest
```

This verification is also part of `:ketraterm-testkit:check` and the test CI
matrix. Thirteen external projects cover all 17 publications through one declared
KetraTerm dependency per project, including direct core/render and optional
completion-host/persistence, shell-integration, Swing-host and workspace roots.
Kotlin and Java sources compile and execute
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

The fixture is copied into testkit's build directory and run with both Kotlin
2.4.0 and the current compiler, each using separate outputs for Gradle module
metadata and POM-only resolution
with Gradle metadata redirection disabled. KetraTerm coordinates resolve
exclusively from the staged repository; third-party dependencies resolve from
Maven Central. The repository's Gradle wrapper and JDK 25 toolchain are reused;
the current-compiler cases track the root Kotlin plugin version.

Existing `TerminalLibraryConsumerCompilationTest` regressions separately check
Java compilation against exported project API variants without the fixture's
Kotlin plugin or testkit classpath.

## Compiled-client upgrades

```text
./gradlew :ketraterm-testkit:compiledClientUpgradeTest
```

This check is included in `publishedConsumerTest`. It runs the thirteen tracked
`baseline/*.jar` clients against the current staged runtime publications in
both metadata modes, using Maven stdlib 2.4.20 and 2.4.0, the latter matching
IDEA 2026.2's boot runtime version (52 executions). This does not run the IDE's
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

Concrete Kotlin/Java parser sinks, core readers, host observers and render
frames/readers exercise required operations and inherited defaults. The render
client retains the original inline lease body, mixes it with Java calls to the
current reader, pins frames across publication, throws from callbacks and proves
that all earlier leases release before their buffer can be recycled. Optional
clients execute bounded directory access, persistence hydration/final flush,
OSC metadata, profile defaults/copy and the supported completion host combinations.
Compared with the original five client/provenance pairs at `e37f5d7f`, parser
and completion client bytes remain identical, with refreshed provenance.
Construction commit `025ccb1a` deliberately replaced host and Swing clients after
incompatible core/session construction changes; D02/D03 later refreshes Swing and PTY.
These eight additional clients
establish extension baselines with their own source/artifact hashes. The 52 cases
verify upgrades from these declared baselines, not compatibility with the original
host and Swing callers. See the [migration and baseline decision](../../../docs/library-compatibility.md#verification-and-baseline-changes).

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
The source hashes identify recording inputs; current source-consumer fixtures
may evolve independently of the retained binaries.

The small jars retain compiled Kotlin/Java callers, including public inline
bodies; library classes resolve from the current publications. They cover
representative executed boundaries; the tracked ABI checks cover the
remaining reviewed declarations. This initial pre-v1 baseline establishes the
upgrade mechanism and does not claim compatibility with earlier releases.

D02/D03 deliberately refreshes only the Swing and PTY retained clients after
their growing data-class configuration APIs become immutable snapshots with
named construction/update callbacks. Before refresh, exactly eight upgrade
cases fail at the removed constructor descriptors; the other eleven clients
remain byte-for-byte unchanged. Source consumers now exercise Java late-field
font resolver selection/clearing, settings drafts, detached snapshots, and
workspace immutable updates. See [configuration migration](../../../docs/library-configuration.md).
