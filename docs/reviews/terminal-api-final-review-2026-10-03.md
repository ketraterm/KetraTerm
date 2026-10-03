# Terminal API final review

## A15 completion after `104db318`

Verified 2026-10-03 in the working tree based on `104db318`. The residual unbind
and disposed-eligibility failures below were reproduced in three independent
regression cases before implementation. They now pass, alongside the original
teardown regressions and additional failure, cancellation, suppression and reuse
coverage. A01–A15 are resolved; G02/G03 remain separate verification work.

Viewport metrics publication is separate from host notification. The component
commits eligibility before callbacks, retains viewport-before-eligibility callback
order and hides ineligible suggestions before eligibility observers run. An
eligibility revision prevents reentrant disposal or unbinding from delivering
obsolete values to later observers. Unchanged snapshot queries do not invalidate
pending notifications. Host failures leave owned state applied; the original
throwable propagates with later independent failures suppressed.

Popup hiding commits logical state and component visibility before the host's
empty update. A guarded viewport-query regression reproduced duplicate updates
while the component still appeared visible; it now requires one update, coherent
callback state and reuse of the same view after re-enabling suggestions.

The cleanup helper remains limited to resource teardown. Normal publication has
explicit state and callback phases, using one shared failure-preservation rule.
Compiled publication, eligibility-application and eligibility-notification methods
contain no object/array allocation or dynamic-lambda creation instructions. This
checks the added control flow, not allocation by called UI/JVM code or a full-frame
performance baseline. No public signatures, dependencies or retained baselines
change. Hosts retain their sessions and reusable views after hide/unbind.

| Check | Result |
| --- | --- |
| Complete Swing and Swing host suites | 1,178 and 84 cases pass, with no failures, errors or skips. This includes thirteen additional Swing cases beyond `104db318`. |
| Compiled-client upgrades | All 54 checks pass against the final artifacts: 52 positive executions and two linkage-negative controls. |
| Compatibility and formatting | `checkKotlinAbi`, root `spotlessApply` and `spotlessCheck` pass; API snapshots and retained binaries remain unchanged. |
| Benchmark harness | `:ketraterm-benchmarks:jmhJar` passes after updating the publication benchmark to use both explicit phases. |

Focused JMH GC profiling of primitive viewport publication on JDK 25.0.3 measured
16.522 ± 1.385 ns/op and 0.196 ± 0.001 B/op (99.9% intervals; two forks, three
warmup and five measurement iterations of one second). Each operation is one
publication within a batch of 1,024; results include amortized Swing EDT dispatch.
This is a narrow measurement, not proof of zero allocation across complete frames
or a replacement for G03.

## Historical A15 verification at `104db318`

Re-reviewed 2026-10-03 after the cleanup fix. The five original A15 regressions
pass. Remaining teardown actions run after host failure or cancellation, retaining
the original throwable and suppressing later failures without self-suppression.
Permanent disposal releases the view and cancels the component scope; peer removal
keeps the component usable for reattachment. The internal shared cleanup function
is justified by these repeated lifecycle operations and changes no public API.

At this baseline, A15 remained open for two callback-failure paths:

- [Popup hiding](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/suggestion/SwingShellSuggestionController.kt#L101) calls the host's final view update before making the component invisible. A throwing update during `unbind()` leaves the previous popup visible despite cleared logical suggestion state. The public hide operation shares this path. The strengthened unbind regression checks actual component visibility; an empty logical state alone does not prove dismissal.
- [Viewport publication](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L2164) notifies the host before updating automatic eligibility. A throwing listener during disposal skips that update; subsequent cleanup clears listeners and repeat disposal returns, leaving a disposed component eligible. Regressions require final eligibility to be false while preserving the original failure and releasing resources.

Attempt owned visibility and eligibility updates independently of host callbacks.
Preserve the first failure and suppress later failures. Hide/unbind must retain
the reusable view and component scope; permanent disposal must cancel the scope.
Sessions remain host-owned. Keep the existing development baseline; these behavior
fixes require no signature changes. Status remains in the
[gap map](../terminal-feature-gap-map.md#final-api-design), before G02/G03 sign-off.

## Verification follow-up at `5facdcbd`

Re-reviewed 2026-10-03 after the A13/A14 fixes. All six original regressions now
pass, with additional coverage for local closure, already-closed attachment,
pending debounce, failure cleanup and recovery. Before adding A15 regressions,
the affected suites passed 1,236 cases; all 54 compiled-client checks, seventeen
ABI checks and Spotless pass.
Public signatures, dependency exports and retained baselines are unchanged.
Unchanged Gradle task outputs were reused where applicable.

A13/A14 are resolved. One related teardown defect remains: A15 below. Preserve
the current development baseline and fix that behavior before delivery sign-off;
this review still does not freeze stable APIs or complete G02/G03.

### A15 — P2: callback failures can interrupt Swing resource cleanup

[Component disposal](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1239)
marks the component disposed, then unbinds it. Unbinding publishes eligibility;
a valid public listener can throw before timer/controller cleanup and component
scope cancellation. Subsequent disposal returns immediately, leaving cleanup
incomplete. This remains possible after the live binding is correctly closed.

Two disposal regressions cover operational failure and cancellation. They require
the original exception to propagate while the timer stops, the component scope
cancels, repeat disposal remains inert and the host-owned session stays open.

The same exception-safety gap exists when
[`removeNotify()`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1019)
finishes scrolling before superclass/ancestor teardown, and when
[`SwingShellSuggestionController.close()`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/suggestion/SwingShellSuggestionController.kt#L107)
hides a custom view before closing it.
A throwing eligibility callback or view update can skip the remaining teardown.
Three further regressions require peer removal with reattachment support and view
closure despite those failures, including preservation of a primary hide failure
when close also fails.
Attempt every owned cleanup and preserve the first failure with subsequent cleanup
failures suppressed. Permanent disposal must cancel the component scope and release
view resources; `removeNotify()` must release peer/ancestor resources while keeping
the component usable for reattachment. Keep ordinary input/settings notification
propagation intact. No new public API is required. Status is tracked in the
[gap map](../terminal-feature-gap-map.md#final-api-design).

### Follow-up validation

| Check | Result |
| --- | --- |
| Complete Swing suite after adding A15 | 1,157 cases: 1,152 pass, only the five new A15 cases fail; no errors or skips. Disposal leaves its scope and timer active; peer removal leaves the peer displayable; both controller-close cases never call the host view's close operation. |
| Swing host suite | 84 pass, including the original A13/A14 regressions and four additional lifecycle cases. |
| Compiled-client upgrades | 54 checks pass: 52 positive executions and two linkage-negative controls. |
| Kotlin ABI | All seventeen publication checks pass; no snapshots or retained client binaries changed. |
| Formatting | Root `spotlessApply` and `spotlessCheck` pass; Swing formatting passes again after the A15 additions. |

The new failures assert required cleanup, not current behavior. This review changes
tests and documentation only. Source-consumer, plugin, native PTY, differential
and performance campaigns were not rerun for this internal lifecycle follow-up.

## Original review at `1f82ab10`

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

## Original findings

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

## Validation at `1f82ab10`

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
