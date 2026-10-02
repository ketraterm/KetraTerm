# Terminal API design review

Reviewed 2026-10-02 at `e37f5d7f66d2cd8df3d9b99a51960fb9c8916002`, after
the initial G01 compatibility work. This review adds tests and documentation;
it does not change production APIs. Open work is tracked in the
[gap map](../terminal-feature-gap-map.md#final-api-design).

The module direction is sound for the requested IntelliJ embedding. The
remaining work concerns public contract shape, ease of composition, and a few
reproduced validation/value defects. Keep the development compatibility baseline;
settle these contracts before promising stable-major compatibility.

## Scope and evidence

The review covers the 17 selected publications, their public contracts and
dependency exports, actual standalone/IntelliJ assembly, and the source/compiled
consumer harness. It traces construction, ownership, threading, cancellation,
failure, disposal, coordinates, configuration growth, external implementations,
and inline/numeric compatibility. Graphify supported navigation; important
conclusions were confirmed in source.

IntelliJ may retain its process hosting, workspace, windows, tabs, shell model,
settings, clipboard, styling, actions and completion system. KetraTerm's Swing
component continues to require a session. Independent renderer extraction stays
outside scope. The existing plugin chooses our local workspace and completion
engine; those product choices are not required by `ui-swing`.

This is a design review, not a new full emulation audit, native-platform proof,
allocation measurement, or claim that every future feature can be added without
new contracts. G02 delivery checks and G03 performance budgets remain separate.

## Findings

### A01 — P1: supplied encoders cannot join the session-owned write queue

**API boundary risk.** The low-level session constructor accepts an already-bound
`TerminalInputEncoder`. Only the default encoder receives `OutboundWriter.append`;
a supplied encoder runs synchronously during admission. The worker writes queued
responses outside that admission lock. An encoder writing directly to the connector
can therefore overtake an earlier response or write concurrently with the worker.

Evidence: [TerminalSession.kt](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L126):120–139,187–195,600–604 and
`OutboundWriter.kt:164–193` in the session module. KDoc delegates ordering to the
caller, but does not expose the shared admission queue. The normal factory path
is healthy; the direct-write example violates the custom path's documented
ordering obligation rather than demonstrating a defect in standard assembly.
`TerminalInputEncoder.setInputPolicy` also defaults to doing nothing, so custom
encoding and session mode reporting can diverge after policy changes.

**Correction:** decide whether custom encoding is a supported production seam.
If so, create it with session-owned mode access and byte output, and define its
policy-update obligation. If it is only a testing escape hatch, remove that
public parameter before freeze. Do not expose raw queue bookkeeping.

**Verification:** after that decision, use a deterministic external encoder to
exercise response/key/paste/replacement ordering, blocked transport progress,
write failure, close, and policy/report agreement. A direct-sink test that ignores
the present precondition would not be a valid regression for standard sessions.

### A02 — P2: configuration evolution needs a deliberate public shape

**Stable-major design risk.** `SwingSettings` has 40 constructor properties and
`SwingHostServices` has 14. Both expose generated constructors, Kotlin default
entry points, `copy` and component methods. `HostPolicy`, input policy, PTY options,
profiles and completion request/model values have related commitments. Adding
a defaulted property changes more than a getter; Java overloads alone do not
preserve already-compiled Kotlin construction and copying.

Evidence: [SwingSettings.kt](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/settings/SwingSettings.kt#L118):118–160, `SwingHostServices.kt:119–135`,
`PtyOptions.kt:65–84`, and the existing [evolution rules](../library-compatibility.md#evolution-rules).
A new popup presenter or diagnostic callback is a concrete pending example.
Current named construction and copying work well in Kotlin; size alone is not
a defect. Java selective configuration is considerably less convenient.

**Correction:** settle the imminent presentation/session options together, then
choose an evolution strategy for the configuration types that are expected to
grow. Preserve useful immutable value semantics. Freeze genuinely fixed values;
use a small, cohesive construction API where configuration growth is required.
Do not convert every data class to a builder or introduce a generic option bag.
Kotlin's current [compatibility guidance](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
also describes experimental version overloading; it does not establish an
automatic solution for generated data-class `copy` or behavioral compatibility.

**Verification:** retained Kotlin default-constructor/copy callers and Java
selective-construction/overload callers must survive an actual compatible option
addition without recompilation. Decide the required Java ergonomics before
choosing a construction pattern.

### A03 — P2: an IDE-owned completion popup lacks a terminal anchor

**Composition gap.** A suggestion view factory replaces an embedded `JComponent`.
The reusable controller owns visibility, selection and dismissal, and sends a
window of at most eight items. A host may instead collect our engine/provider
flows and own its popup, but cannot obtain authoritative component-local cell
bounds. Padding, gutter, bidi mapping, metrics and fractional scrolling are private.

Evidence: [SwingShellSuggestionView.kt](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/suggestion/SwingShellSuggestionView.kt#L145):145–178,
`SwingTerminal.kt:1035–1081`, and `SwingLiveCompletionBinding.kt:127–159,324–370`.
The live binding also targets the integrated presenter only. A native `JBList`
inside our component, as used by the current plugin, is different from an
independently owned IDEA popup/controller.

**Correction:** expose one EDT geometry operation using the existing mapping,
with explicit unavailable/outside-viewport behavior. If a host should reuse our
automatic request coordinator with its own popup, add a narrow request/hide
attachment contract at that boundary. Otherwise document host-owned orchestration.
Neither choice needs a new renderer or a general UI framework.

**Verification:** anchors under primary/alternate padding, prompt gutter, bidi,
fractional scrolling, resize and unavailable frames; coordinator reuse additionally
needs focus, cancellation, rebinding, stale acceptance and disposal checks.

### A04 — P2: normal session assembly requires an undeclared second capability

**Type-safety/usability gap.** `TerminalBuffers.create` returns `TerminalBuffer`,
while KDoc promises `TerminalRenderFrameReader` too. `TerminalSession.create`
accepts the first interface and then casts to the second. A legitimate
`TerminalBuffer by delegate` decorator compiles but fails normal assembly unless
it also knows to implement the render contract.

Evidence: [TerminalBuffers.kt](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/TerminalBuffers.kt#L32):32–47, `TerminalSession.kt:1108–1111`.

**Correction:** make the normal factory's combined capability explicit, or accept
an explicit render reader in standard assembly. Keep separate collaborators for
implementations that split those responsibilities; do not make every headless
core implementation render-capable merely to remove a cast.

**Verification:** isolated Kotlin/Java construction without casts, a delegated
render-capable implementation, and rejection of genuinely incompatible assembly
before taking transport ownership. A combined return type would be an intentional
pre-stable ABI change, requiring reviewed snapshots and a new baseline decision.

### A05 — P2: parser substitution cannot retain all normal session services

**Extension limitation.** The low-level constructor accepts a parser/adapter,
but omits the normal factory's startup-command and session clipboard-read wiring.
Decorating a parser while retaining consent, deadlines, policy revalidation,
ordered replies and startup submission requires rebuilding private coordination.

Evidence: [TerminalSession.kt](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L1129):108–139,1129–1162.
This does not affect supplying a host-owned shell model through the normal factory.

**Correction:** explicitly decide whether complete parser substitution is a
supported extension. If required, allow parser creation around the normally
assembled sink/services. Keep the advanced constructor's caller-owned wiring
contract distinct; adding unrelated factory layers is unnecessary.

**Verification:** an external parser decorator retaining OSC 52 denial/success/
unavailable behavior, DECCOLM connector resize, shell authority, startup cancellation,
EOF and disposal. Add this consumer when the supported contract is chosen.

### A06 — P2: external implementers need an evolution proof

**Design/verification risk.** `TerminalCommandSink`, core role interfaces and
render-frame contracts are implementer commitments. A new abstract semantic
member breaks old concrete implementations. A blanket default no-op would conceal
missing terminal behavior. Current retained parser clients use a dynamic proxy;
host clients use the new library's adapter and `HostEventSink.NONE`.

Evidence: [TerminalCommandSink.kt](../../ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/spi/TerminalCommandSink.kt#L33):33, `TerminalRenderFrame.kt:201`, and
`src/consumerTest/parser/src/main/java/consumer/JavaConsumer.java:25` in testkit.
The existing ABI check detects surface changes; these fixtures do not exercise
an old concrete sink executing the new parser's command path.

**Correction:** classify principal interfaces as host implementation contracts
or library-produced consumer views, and retain real external implementations of
the former. For an actual new semantic family, choose a focused optional capability
and truthful unsupported behavior, rather than extending every required method
set. Preserve query failure responses and response-family denial. No generic
protocol registry is warranted.

**Verification:** old concrete Kotlin/Java implementations against new parser,
host and render publications, including inherited defaults and unsupported new
capabilities. Do not regenerate old binaries merely to accept a break.

### A07 — P2: public inline leasing embeds publisher internals in callers

**Maintenance risk, not a reproduced race.** `TerminalRenderPublisher.readCurrent`
inlines lock acquisition, front-index selection and reader-count mutation. A
future publisher must cooperate with that old algorithm, not just keep helper
signatures. The current retained reader exercises one lease; current-source
concurrency tests execute the current inline body.

Evidence: [TerminalRenderPublisher.kt](../../ketraterm-render-cache/src/main/kotlin/io/github/ketraterm/render/cache/TerminalRenderPublisher.kt#L147):147–173; the
[compatibility contract](../library-compatibility.md#evolution-rules) already
recognizes this constraint.

**Correction:** consider keeping the callback inline while moving lease bookkeeping
behind small non-inline acquire/release operations. Measure before changing the
hot path. Alternatively accept the existing representation commitment explicitly.
Public packed render values are appropriate; borrowed cache arrays deliberately
remain lease-bound mutable storage.

**Verification:** an old compiled reader holding a lease during new publication,
callback failure and concurrent reads; recycling exclusion, balanced release and
final-frame availability. A signature dump cannot establish those properties.

### A08 — P2: two retained values expose mutable Java lists

**Reproduced defects.** `SwingShellSuggestionViewSnapshot.visibleSuggestions` and
`SwingDialogRequest.options` use `toList()`. For multiple items their public Java
lists permit replacement, insertion, removal and clearing. The first changes
selection/overflow after validation; a reordered view can report an index for a
different controller item. The controller's complete ranking is separately copied.

Dialog buttons capture labels, but their results later use the current
`request.options.indexOf(value)`. Reordering the returned options can reverse
the index interpreted as Allow/Deny. This requires mutation by host code; it is
not evidence of a terminal-byte-triggered consent bypass.

Evidence: `SwingShellSuggestionView.kt:41–56`, [SwingDialogRequest.kt](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingDialogRequest.kt#L37):37,
`SwingMessageDialogs.kt:41,71,101–102`, `SwingClipboardReadPrompt.kt:76`.

**Correction:** publish owned unmodifiable lists while preserving the existing
`List` signatures. Keep the input-list defensive copies.

**Verification:** the two new Java module test classes exercise four mutation
operations each and assert stable labels, selected item and indices. All eight
cases currently fail; existing input-list-copy tests continue to pass.

### A09 — P2: public projection bounds checks overflow before rejection

**Reproduced defects.** A two-item suggestion viewport beginning at `Int.MAX_VALUE`
passes `start + size <= total` after overflow, allowing a negative absolute selection.
Shell `copyRecords`, `copyViewport`, `copyCommandOutputRange` and
`copyCommandBlockRange` similarly accept overflowing destination slices. An empty
model can silently return after invalid clearing loops skip their work.

Evidence: `SwingShellSuggestionView.kt:70` and
[TerminalShellIntegrationState.kt](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalShellIntegrationState.kt#L607):607,652,826–845,914–930.

**Correction:** widen the viewport sum or validate through subtraction; use
overflow-safe slice checks for the shell arrays. Validate every destination
before clearing or writing. No module/API redesign is needed.

**Verification:** five new rejection cases currently fail. Valid near-limit
viewport and exact-end zero-count projection controls pass. Tests retain destination
sentinels and cover all four shell projection entry points.

### A10 — P3: provider diagnostics and callback threading need completion

**Usability/documentation risk.** The completion engine accepts a source-failure
handler; Swing's provider boundary only prints to `System.err` before hiding.
Hosts can wrap their provider, but centralized IDE diagnostics require that extra
work. The public adapter samples `contextProvider` in its caller's context,
normally a background dispatcher, without explicitly requiring a thread-safe
snapshot in that parameter's KDoc.

Evidence: [SwingTerminal.kt](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1795):1795–1800, `SwingCompletionSuggestionProvider.kt:31–49`,
`TerminalCompletionEngines.kt:44–49`.

**Correction:** document callback threading. Decide whether provider failure
reporting is intentionally wrapper-owned or merits one host callback alongside
the presentation contract. Preserve cancellation and cleanup; avoid an error bus.

**Verification:** failure reported once, cancellation excluded, stale work unable
to affect a new request, and immutable context safely obtained away from the EDT.

### A11 — P3: direct cluster copying has no capacity-discovery operation

**Ergonomic limitation.** `TerminalLine.readCluster` requires an array large enough
for the entire cluster and guarantees no fixed public upper bound, but exposes
neither the required length nor partial copying. Direct consumers must guess or
recover from an exception. Its allocate-once KDoc cannot guarantee enough capacity
for every core-written cluster.

Evidence: [TerminalLine.kt](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/api/TerminalLine.kt#L55):55–73, `ClusterStore.kt:255–269`.

**Correction:** choose whether this is a first-class direct-core operation. If so,
provide truthful size discovery; otherwise direct complete reads toward the existing
render-frame cluster sink and correct the allocation guidance.

**Verification:** copy a long directly written cluster without relying on parser
limits or implementation exception text. The standard renderer already uses the
appropriate frame path.

### A12 — P3: compatibility execution remains representative

**Coverage improvement.** All 17 publications have ABI checks; five retained
client roots reach 12 artifacts. Completion-host/persistence, shell integration,
Swing-host and workspace lack retained execution fixtures. The minimum Kotlin
2.4.0 compiler was manually verified; ongoing source fixtures use current 2.4.20.
The 20 positive upgrades and two negative linkage controls are useful, but do not
prove every public default, external implementer, inline algorithm or IDE classloader.

Evidence: [CompiledClientUpgradeTest.kt](../../ketraterm-testkit/src/test/kotlin/io/github/ketraterm/testkit/CompiledClientUpgradeTest.kt#L43):43, `ketraterm-testkit/build.gradle.kts:313`
and the [consumer documentation](../../ketraterm-testkit/src/consumerTest/README.md).

**Correction:** prioritize concrete implementers and chosen host composition
alongside the APIs being revised. Add a continuous minimum-compiler case and
optional-artifact clients where supported promises need them. Avoid exhaustive
reflection tests that merely mirror declarations. Actual installed IDE/native
validation belongs to G02.

## Composition and future-change checks

| Scenario | Assessment |
| --- | --- |
| Host owns tabs, windows, process and settings | Already supported: supply a connector/session, resolved settings and host services. No workspace or PTY dependency is forced by Swing. |
| Host owns shell scripts, protocol and model | Already supported by one selected neutral producer. The bridge must publish stable terminal line IDs, UTF-16 editing offsets and fresh grid anchors in output order; IDEA document offsets are not those identities. |
| Own engine with our popup; our engine with own embedded view | Already supported through independently supplied provider, handler and view factory. Existing plugin assembly demonstrates the native embedded-view combination. |
| Own IDE popup/controller with our completion results | Results/edit helpers are reusable. A03 supplies the missing authoritative anchor; automatic orchestration reuse needs its own explicit decision. |
| New completion source or optional persistence | Existing source/failure/lifecycle contracts preserve ownership. No parser/core/UI boundary move is needed. |
| New transport implementation | Existing connector/session boundary fits; workspace deliberately remains a local-session composition. Transport-specific permissions, lifecycle and paste policy still require implementation and tests. |
| New style, policy or host service | Ownership is correct; A02 concerns how new configuration is exposed compatibly. |
| New protocol/input/render family | Keep each existing owner. A06 concerns old implementers. The mode word uses bits 0–18 and 20–62; preserve that projection rather than repacking it for additional coherent state. Graphics will need bounded parser/core/render contracts, as already tracked; this review does not implement them. |
| New cache/publisher algorithm | A07 requires compatibility with old inline readers and preserved packed values. |

Current factory setup is reasonable for Kotlin. Advanced assembly and native
popup composition require more knowledge than their signatures expose. Java
basic construction and synchronous hooks work, but late configuration properties,
Kotlin function defaults and genuinely suspending custom providers deserve a
concrete Java-host example before adding interoperability machinery.

Optional `ui-swing-host` helpers bring the completion artifact through `api(...)`
even when a host only wants search/dialog chrome. The reusable search bar has its
own visual palette; hosts can use the terminal's search API with native chrome.
These are conscious packaging/presentation limits, not reasons by themselves to
split modules. Likewise one active viewport per session remains intentional.

## Implementation sequence

1. Fix A08/A09 as small correctness changes using the added regressions.
2. Settle A01/A04/A05 together: standard and advanced pipeline construction,
   capability requirements, and one session-owned output path.
3. Settle A02/A03/A10 together: configuration evolution and independently selected
   completion presentation, orchestration and diagnostics.
4. Resolve A06/A07/A11 and extend A12 with concrete consumer proofs for the chosen
   contracts. Keep packed storage and synchronous hot paths; measure lease changes.
5. Review/update the development snapshots deliberately, then undertake G02/G03
   using the selected final shape. These recommendations do not freeze APIs now.

## Regression validation

Fifteen cases were added: eight Java mutation cases, five overflow rejection
cases, and two valid-boundary controls. Existing tests were not weakened.

| Suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Session | 258 | 4 | 0 |
| Swing | 1,136 | 5 | 0 |
| Swing host | 67 | 4 | 0 |
| Total | 1,461 | 13 | 0 |

All thirteen failures are the new A08/A09 regressions; no existing case failed.
The tests compile, and both new positive boundary controls pass. Root
`spotlessApply` and `spotlessCheck` pass. The first Swing-host run exposed a new
test's unavailable parameterized-test dependency; it was corrected to ordinary
JUnit tests without changing dependencies, then the full host suite was rerun.
`graphify update .` completed with four partial-extraction warnings; source,
compilation and test results remain the evidence for the conclusions.

```text
./gradlew spotlessApply
./gradlew :ketraterm-session:test :ketraterm-ui-swing:test :ketraterm-ui-swing-host:test --continue -Djava.awt.headless=true
./gradlew spotlessCheck
```

The final state was checked through those three module reports; this turn did
not rerun root, plugin, native PTY, differential or published-client suites.
Architecture recommendations include their required future verification, rather
than tests asserting an API that has not been chosen. No changelog or ABI snapshot
updates accompany review-only work. The enabled regressions make CI fail until
the four demonstrated defects are corrected.
