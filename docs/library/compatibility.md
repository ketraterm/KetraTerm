# Library compatibility

KetraTerm's published libraries support Kotlin and Java hosts. The current 0.x
API snapshots are a development baseline, not a stable API freeze or a promise
of compatibility with earlier 0.x releases. Starting at 1.0, releases within one
major version preserve supported source, binary, and behavioral contracts.

## Supported boundary

| Offering | Published JVM modules |
| --- | --- |
| Headless pipeline | protocol, parser, core, host, input, render-api, render-cache, transport-api, session |
| Swing view | ui-swing |
| Local processes and shell metadata | pty, shell-integration |
| Completion | completion, completion-host |
| Swing host controls | ui-swing-host |

Names have the `ketraterm-` prefix. The constraints-only BOM and dependency-only
headless/Swing entry points are additional metadata publications. Use one
KetraTerm version throughout the dependency graph; see [installation](../../README.md#using-the-libraries)
and [Maven setup](../../ketraterm-bom/README.md#maven).

Public/protected declarations in published libraries are supported contracts.
Kotlin internal/private declarations are excluded, except published inline
bridges that already-compiled callers can reference. Workspace, completion
persistence, app, testkit, benchmarks, and IntelliJ product implementation APIs
are outside the supported publication boundary.

Kotlin 2.4 and JDK 25 are the minimum supported consumer versions. The build uses
Kotlin 2.4.20, JVM target 25, and JVM default-method compatibility bridges.
Consumer checks exercise Kotlin 2.4.0/2.4.20 and both Gradle metadata and POM-only
resolution. A host replacing dependencies with a platform runtime must verify
that runtime separately.

## Evolution rules

- Preserve JVM signatures, Kotlin metadata, defaults, and documented semantics.
- Preserve constructor, copy, component, and default-call shapes of value data
  classes. Adding a defaulted constructor field can still break binaries.
- Grow configurable snapshots through their existing builder/update entry points;
  preserve existing property types and defaults.
- Preserve overloads and Kotlin default-call methods. Java overload generation
  does not make Kotlin default-argument changes binary compatible.
- Treat interfaces as implementer contracts. New abstract members and new
  enum/sealed variants need source and compiled-consumer assessment.
- Preserve public packed values, encoded enum order, constants, and inline bridge
  behavior; signature checks cannot detect semantic drift.
- Keep public dependency types available through publication metadata.
- During a stable major version, deprecate with a replacement before removal;
  incompatible removal belongs in a new major release.

## Verification and baseline changes

Supported modules use explicit API mode and checked-in ABI snapshots. Published
consumer fixtures compile and run independent Java/Kotlin clients; retained
binaries test upgrades without recompilation. Deliberate linkage-failure controls
check that the upgrade harness detects missing symbols.

`./gradlew publicationChecks` runs the library publication gates against a local
repository without uploading. The [consumer fixture guide](../../ketraterm-testkit/src/consumerTest/README.md)
explains individual checks and baseline refreshes. A deliberate development
baseline refresh must record the break and migration; it cannot be presented as
proof of compatibility with the removed API.

ABI checks do not establish threading, lifecycle, coordinate, or protocol
correctness. Those require semantic tests and the module contracts. Product
release verification remains separate; see the [gap map](../terminal-feature-gap-map.md#product-and-release-work).

## Development migrations

These earlier 0.x changes require affected consumers to recompile. Detailed
release history remains in the [changelog](../../CHANGELOG.md).

| Earlier shape | Current integration |
| --- | --- |
| Growing settings constructors, named-argument copies, destructuring | Immutable `create`/`copy` callbacks or Java builders; use getters. |
| Swing history/window-permission settings | Core/PTY history configuration and session host policy. |
| Session render-publisher access | `readPublishedFrame`; retain mutable producer references at assembly. |
| Public integer render leases | Scoped `readCurrent` or session reads; see [reader ownership](render-ownership.md). |
| Constructor-bound suggestion provider/edit target | EDT provider/target setters and request-scoped editing authority. |
| Global suggestion feedback handler | Request-owned source/interaction observer with actual acceptance results. |
| Old suggestion show/request/candidate shapes | Begin, publish/collect, and present an interaction; update custom presentation to snapshot-based callbacks. |
| Pre-bound custom input encoder | `TerminalInputEncoderFactory` with session-owned output; implement synchronous input-policy updates. |
| Earlier buffer factory return type | `TerminalRenderBuffer`; changed JVM return descriptors require recompilation. |
| Non-null shortcut lookup | Handle null for legitimately unbound actions. |
| Workspace/persistence development artifacts | Product-only modules; library hosts own these concerns. |

See [configuration](configuration.md), [session assembly](../../ketraterm-session/README.md#custom-assembly),
and [Swing APIs](../../ketraterm-ui-swing/README.md) for current usage.
