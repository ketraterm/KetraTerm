# Library compatibility

KetraTerm's published JVM libraries are intended for Kotlin and Java hosts.
The tracked API snapshots are a reviewed development baseline, not a claim that
earlier 0.x releases were binary compatible. Starting at 1.0, releases within
one major version preserve supported source, binary and behavioral contracts.

## Supported boundary

Public and protected declarations in each published library are part of the
contract. Kotlin `internal` and private implementation details are excluded,
except `@PublishedApi` declarations referenced by public inline functions:
already-compiled Kotlin callers may link directly to them. Application,
benchmark and testkit implementation APIs are not published library contracts.

The root build explicitly selects the published libraries; a new Kotlin module
is not published automatically. The selected artifacts include optional composed
libraries, such as local PTY hosting, workspace, completion adapters and Swing
host actions. Composition does not make every implementation helper an embedding
API. Keep library-local helpers internal/private; cross-module visibility needs
its own contract justification. The current selected libraries each have a real
host or runtime boundary. App, benchmarks, testkit and the nested IntelliJ product
build are outside this library publication set.

The reviewed extension points follow the existing module ownership:

| Library group | Host extension boundary |
| --- | --- |
| Protocol, parser, core, host, input | Semantic command sinks, headless core contracts, host callbacks and policies, input events and byte output |
| Render API and cache | Primitive copied-frame readers, owned cache storage and leased publication |
| Transport and session | Ordered connectors, session assembly, serialized input and borrowed frame access |
| Shell integration | Neutral host-owned metadata contracts in session; optional OSC producer in its own library |
| Swing and Swing host | EDT component lifecycle, host services, immutable settings, suggestions and host-neutral actions |
| Completion, host and persistence | Completion sources, evaluation and learning; separately owned filesystem access and sanitized persistence |
| PTY and workspace | Local process lifecycle, profiles, local-session creation and workspace callbacks |

Use the module contracts for threading, ownership and coordinates. Grid reads,
mutations and parser calls require external serialization; atomic mode reads
follow their narrower method contracts. A session exclusively owns mutation of
its supplied pipeline. The low-level session constructor requires collaborators
for the same terminal. Session-managed pipelines are accessed through session
operations and borrowed render frames; the mutable core is not exposed by the
session. A caller that retained the core supplied at construction may inspect it
only after `state` reaches `TerminalSessionState.Closed`, not merely when `isClosed`
becomes true. Rendering leases end with their callback;
cache arrays must not escape that lifetime or be modified by the consumer.
Swing component operations require the EDT; disposing a view does not close its
host-owned session. Host shell producers and supplied dispatchers remain owned
by the host.

The initial review deliberately narrows earlier development APIs: raw packed
core attributes belong to core implementation, and published caches must be read
through `readCurrent` leases. Inspect semantic attributes or public render words
instead. Selection packing, shell projection probes, configuration-path test
parameters, the directory scanner's test clock and search-bar component
construction stay inside their owner modules.
The custom session constructor retains parser/adapter injection but owns its
admission lock; callers do not supply a shared lock. These are pre-stable API
changes, not compatible upgrades promised for earlier 0.x clients.

The build uses Kotlin 2.4.20 with language/API version 2.4, JVM target 25 and
the `ENABLE` JVM default-method mode (including compatibility bridges).
Source consumers are verified with Kotlin compilers 2.4.0 and 2.4.20; Kotlin 2.4
and JDK 25 are the supported minimums. Publications resolve stdlib 2.4.20;
retained clients also run with stdlib 2.4.0, checking its actual version and loaded
jar. IDEA 2026.2's JVM 25 boot runtime owns Kotlin 2.4.0; its separately bundled
Kotlin compiler libraries are not the IDE boot runtime. Hosts replacing Maven's
stdlib with their platform runtime must verify that runtime. Older Kotlin/JDK
versions are unsupported. Raising the minimums within a stable major version
requires a separate compatibility decision.

## Evolution rules

- Preserve JVM signatures, Kotlin metadata and documented behavior. A source
  change that recompiles successfully may still break an existing binary.
- Configuration and value data classes retain their constructor, `copy`,
  component and generated default-call shapes. Adding a defaulted primary
  constructor property is a binary change. Add a separate API only when a real
  requirement warrants it; do not introduce builders in anticipation of one.
- Preserve old overloads and Kotlin default-call entry points. `@JvmOverloads`
  serves Java overloads; it does not make changes to Kotlin default arguments
  binary compatible.
- Treat interfaces as implementer contracts. New abstract members break existing
  host implementations. A default member needs an already-compiled implementer
  check, including the configured JVM default-method mode.
  New enum or sealed variants can also break exhaustive Kotlin `when` consumers;
  assess their source and runtime behavior before calling an addition compatible.
- Public inline bodies and `const` values are copied into consumers. Preserve
  numeric mode bits, enum ordering where ordinals are encoded, packed render
  words and their meanings; a signature comparison alone cannot detect drift.
  Publisher changes must also honor the lease algorithm embedded in previously
  compiled readers, not just retain their `@PublishedApi` helper signatures.
- Keep public dependency types available to isolated consumers through generated
  Maven metadata. Resolve each KetraTerm dependency from the same release;
  independently mixing library versions is not covered.
- Deprecate with a replacement and migration guidance at warning level first.
  Keep existing source and binary entry points for the stable major version;
  removal or error-level deprecation belongs in a new major release.

## Verification and baseline changes

Published modules use strict explicit API mode and Kotlin's built-in ABI
validator over the actual Maven publication jars, with no package allowlist.
Each module tracks `api/<module>.api`; `checkKotlinAbi` also runs with its `check`
task. The repository test workflow checks all published APIs and formatting.

```text
./gradlew spotlessApply
./gradlew checkKotlinAbi
./gradlew :ketraterm-testkit:publishedConsumerTest
```

The [isolated consumer fixtures](../ketraterm-testkit/src/consumerTest/README.md)
check current Kotlin/Java source compilation and runtime behavior with both
Gradle module metadata and POM-only resolution. The separate compiled-client
upgrade check runs retained client binaries against current publications without
recompiling those clients. Deliberately missing classes and methods prove that
the harness reports linkage failures. This is representative integration
coverage, not an exhaustive test of every public member or every older release.

Run `:<module>:updateKotlinAbi` only after reviewing an intentional API change.
Review generated default methods, data-class members and `@PublishedApi`
entries alongside the source. A dump update records a change; it does not prove
that the change is compatible. Retained clients must continue to pass after a
compatible change; do not regenerate them to make an upgrade failure disappear.
Their sources and recording provenance document an intentional new baseline.
For a breaking major release, retain the previous major's baseline on its
maintenance branch and record the new one with migration notes.

ABI snapshots do not prove complete Kotlin source compatibility, correct
lifetime, concurrency, ownership, terminal semantics or public constant values.
Those properties remain covered by source review, module regressions,
compiled consumer assertions and the documented contracts.

Generated Java overloads also require consumer coverage: Kotlin's validator omits
the scanner's generated no-argument secondary-constructor overload. The isolated
Java consumer checks every supported scanner constructor. These checks remain
representative rather than exhaustive JVM-signature coverage. Follow
[Kotlin's compatibility guidance](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
and [ABI validation documentation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html)
when assessing changes.
