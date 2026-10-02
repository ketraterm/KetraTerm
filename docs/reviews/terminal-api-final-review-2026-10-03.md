# Terminal API final review

Reviewed 2026-10-03 at `1f82ab10` on `audit/terminal-api-design`, including the
four fix groups following the [original review](terminal-api-design-review-2026-10-02.md).
This review adds regressions and corrects compatibility documentation; it changes
no production API and regenerates no client binaries.

The resulting public shape supports the intended IntelliJ integration. The new
factories and capability contracts enforce real ownership boundaries; no further
module split or configuration framework is justified. Accept the current ABI
snapshots and explicitly declared clients as the development baseline. Resolve
the two native-popup behavior defects below before proceeding to G02 delivery
checks. This is not a stable API freeze or completion of G03.

## Remaining findings

### A13 — P2: session termination does not invalidate native completion

The optional [live binding](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingLiveCompletionBinding.kt#L196)
observes shell-edit revisions. Session shutdown cancels that tracker and publishes
`Closed`; it need not publish another edit. With focus unchanged, a native target's
popup and automatic request therefore remain active. The embedded controller
separately observes session state, so its behavior does not verify this path.

Observe termination in the shared binding and invoke its existing cancellation
and hide operation. Keep target resources host-owned. Two regressions establish
an outstanding request, then remotely close or fail the real session without
another host edit or focus change. They assert dismissal and request cancellation,
without requiring a particular terminal revision value or using sleeps.

### A14 — P2: terminal callbacks discard native target failures

[SwingShellSuggestionTarget](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingShellSuggestionTarget.kt#L26)
promises that exceptions reach the calling operation or observation scope.
Input-invalidation and eligibility callbacks instead discard them through ignored
`runCatching` results in [SwingTerminal](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1931).
This includes cancellation; a host cannot observe failed popup cleanup reliably.

Honor the declared propagation contract consistently and preserve cancellation.
Four independently executed regressions cover operational failure and cancellation
through `clearScreen()` and settings-driven eligibility loss. Throws are enabled
only after attachment settles. Direct close already detaches even when hide fails;
retain that guarantee.

Both findings are tracked under [Final API Design](../terminal-feature-gap-map.md#final-api-design).

## Disposition of the original findings

| Findings | Verified result |
| --- | --- |
| A08/A09 | Retained lists defensively copy and reject Java mutation. Projection and copy ranges reject overflow before changing state or destination. All thirteen original failing regressions pass. |
| A01/A04/A05 | Custom encoders are created with session-owned output and independent admission/bulk state. Combined or separate render capabilities are explicit. Parser factories preserve normal clipboard, startup, resize and EOF services. Failure before construction completes leaves connector ownership with the caller. |
| A02/A03/A10 | Settings/services keep fixed constructor and generated default-call shapes. New view-lifetime functions are additive EDT operations. Native targets receive authoritative clipped cell geometry; provider failures reach host diagnostics after cleanup, excluding obsolete requests and cancellation. A13/A14 are additional native-target defects. |
| A06/A07 | Concrete external implementations exercise required members and inherited defaults. An authentic retained inline reader and current Java reader share leases across concurrent publication, callback failure and recycling. Non-inline bookkeeping preserves the earlier inline representation. |
| A11/A12 | Complete arbitrary cluster reads use the existing length-bearing frame sink; direct line copies require known capacity. Thirteen consumer roots reach all seventeen publications, with both source compilers and metadata modes continuously gated. Coverage remains representative. |

IntelliJ can supply its own connector and shell model while retaining the normal
session services and reusable renderer. It can independently own containers,
clipboard, settings/styling, completion sources and popup/dialog presentation.
The optional OSC producer, workspace and PTY launcher are unnecessary for that
composition. Hosts may reuse the embedded completion view, automatic native-target
coordination, or result adapters with their own controller. Target acceptance must
check current context; target collection and popup resources remain host-owned.

The new public types are justified: `TerminalRenderBuffer` declares a required
combined capability; encoder/parser factories bind implementations to assembled
session services; `SwingShellSuggestionTarget` separates automatic coordination
from popup ownership; the failure handler supplies host diagnostics. Existing
module boundaries remain appropriate. Fixed configuration shapes constrain future
constructor growth; selective Java construction remains less convenient than
Kotlin named arguments. Neither warrants speculative builders.

## Deliberate development baseline

The [compatibility contract](../library-compatibility.md#verification-and-baseline-changes)
records migration for the changed core factory return descriptor, session
construction/default-call signatures, and required encoder policy operation.
These are intentional incompatible development changes, not compatible upgrades.

Compared with the original baseline at `e37f5d7f`, construction commit `025ccb1a`
replaced `host.jar` and `ui-swing.jar`. The original host bytecode calls
`TerminalBuffers.create(III):TerminalBuffer`; the current method returns
`TerminalRenderBuffer`, changing its JVM descriptor. Original Swing bytecode also
references removed core/session default-call descriptors. Parser, completion and
PTY client bytes remain identical, with refreshed recording provenance. The eight
extension clients have their own recorded inputs. Historical jar/provenance pairs
remain available at `e37f5d7f` in Git.

The earlier claim that all five original clients stayed unchanged was incorrect
and is corrected. The current 52 positive executions establish upgrades from the
declared development baselines. They do not prove the original host/Swing clients
compatible. No compatibility shim or additional baseline regeneration is justified
for this pre-stable decision. Retained old inline leasing remains a real
compatibility commitment, not permission to replace its storage representation.

## Validation

| Check | Result |
| --- | --- |
| Existing root tests | 4,760 cases, zero failures/errors, 42 skipped/aborted before adding the six review regressions. |
| Final Swing-host suite | 80 cases: the original 74 pass; exactly the six new A13/A14 cases fail at the intended behavioral assertions. No setup or compilation failures remain. |
| Published source consumers | All thirteen roots compile/run with Kotlin 2.4.0 and 2.4.20, each in Gradle-metadata and POM-only modes. |
| Compiled-client upgrades | 52 positive executions and both deliberate linkage-failure controls pass. |
| ABI and formatting | All seventeen ABI checks pass; root and owner Spotless checks pass after applying formatting. |
| IntelliJ plugin | Test task passes with 244 recorded cases and no failures/skips; unchanged outputs were up-to-date. |
| Differential smoke | Xterm, resize/reflow and cursor/wrap campaigns pass, each with 100 generated cases; the four Node oracle self-tests also pass. |
| Benchmark harness | `:ketraterm-benchmarks:jmhJar` compiles; fresh lease measurements are below. |

The broad root command initially failed formatting when newly added review tests
were encountered. After `spotlessApply`, final owner compilation succeeds and its
only failures are A13/A14. Their exact identities are in
`SwingLiveCompletionBindingTest`; the assertions expect popup dismissal and
exception propagation, not the current defective behavior.

Gradle reused unchanged task outputs where applicable. The 42 root omissions
comprise 14 accepted R06 streaming-placement aborts, 16 opt-in native PTY cases,
four unavailable zsh/fish cases, and eight separately gated conformance cases.
Smoke profiles were executed separately; nightly campaigns, native PTY opt-ins,
installed products and Plugin Verifier were not run here. They remain outside
this API review and do not become verified delivery claims. No changelog entries
are added for analysis or regressions alone.

## Lease measurement

Fresh JMH 1.37 measurements used Temurin 25.0.3 on Windows, one thread, two forks,
three one-second warmup iterations and five one-second measurement iterations,
with the GC profiler and the existing empty/published lease benchmark.

| Case | Average time | Normalized allocation | GC collections |
| --- | --- | --- | --- |
| No frame | 3.685 ± 0.248 ns/op | 0.000025 B/op | 0 |
| Published frame | 11.178 ± 2.213 ns/op | 0.000077 B/op | 0 |

The same measured jar reports zero owner-thread allocated bytes over one million
warmed Java reads with a reused callback, for each case. This measures lease
bookkeeping and a primitive read, excluding contention, frame copying, Swing and
allocating callbacks. It does not establish a release latency budget or prove
zero allocation throughout rendering.

Results are in `build/api-final-review/lease-current.json`; the copied measured jar
has SHA-256 `205d3f971bab1cee05bb2520c8557e3dd24fb304af0779e9eaaf211e508c270e`.
The JSON hash is `8bb8d57f63f91262386bf0bae4bae9d88537933f42263e509d0216d0530d4cf7`.
Reproduce with the built JMH jar and:

```text
java -jar <jmh-jar> io.github.ketraterm.benchmark.TerminalRenderLeaseBenchmark.readLease -bm avgt -tu ns -wi 3 -i 5 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff <results.json>
```

The earlier before/after reports measured published reads at
8.473 ± 0.921 and 8.498 ± 0.200 ns/op. Their intervals overlap, but the reports name
the same mutable jar without image hashes. They support only a local scoped
observation; this review cannot certify their exact before/after images or claim
a performance improvement. G03 must establish reproducible release budgets.
