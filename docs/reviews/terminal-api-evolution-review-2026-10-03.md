# Terminal API adoption and evolution review

Reviewed on 2026-10-03 at `f632fbe3` on `audit/terminal-api-design`, initially
with a clean working tree. This review adds correct-behavior startup regressions
and documentation. Production code, public signatures, compatibility baselines
and changelogs are unchanged. Ownership and closure status live in the
[gap map](../terminal-feature-gap-map.md#api-adoption-and-evolution).

## Assessment

Do not freeze the API yet. The M corrections have a green root test and
compatibility baseline, but compatibility with a declared development API does
not establish that its ownership, construction or extension cost is acceptable.
One additional startup defect is reproduced below. The remaining findings
separate source-confirmed boundary problems, design decisions and missing
adoption evidence; they are not ten newly reproduced runtime bugs.

Keep the existing module split. The parser/core/host/input boundaries, optional
shell producer, copied render storage, completion engine and host adapters have
real independent responsibilities. Neither another shell module nor a generic
extension framework is justified. Cohesive controllers, assembly factories and
provider interfaces should stay where they enforce actual ownership or allow an
independent host implementation. File size alone is not a refactoring reason.

The review traced public construction, real consumers, mutable ownership,
threading and lifecycle, publication/inline ABI, configuration growth, shell
metadata and popup composition. Graphify supported navigation; source was used
to verify important edges. G02 delivery gates and G03 performance qualification
are outside this review.

## What an embedding host can already own

| Host requirement | Current composition | Remaining evidence or decision |
| --- | --- | --- |
| Parser/core with its own renderer | Public parser, buffer and render contracts; no session required | Preserve focused optional capabilities for genuinely new semantic families. |
| Our interactive Swing view in its own windows/tabs | `TerminalSession` plus `SwingTerminal`; workspace is optional | Clarify configuration ownership and demonstrate a small complete assembly. |
| Its own transport | `TerminalConnector` supplied to the session | Align observable startup readiness with transport and input admission. |
| Its own shell scripts and model | Selected `TerminalShellIntegrationFactory`, editing/readiness flows and bounded metadata state | Prove exact marker/output ordering through a host decoder. |
| Its own completion and native popup | Provider/controller supplied by the host; optional `SwingLiveCompletionBinding`; cell bounds from the terminal | Exercise the actual request/acceptance/disposal path and settle reusable UX policy. |
| Its own clipboard, fonts, palette and chrome | Swing host services; session `TerminalClipboardReader`/`HostEventSink` for OSC 52; resolved settings and independent UI actions | Improve selective construction and styling of optional reusable chrome. |

IntelliJ's upstream Ghostty integration already wraps VT input with its own
[custom-command sniffer](https://github.com/JetBrains/intellij-community/blob/650ce34fddae6f8d50e99d0419139d39f9df052f/plugins/terminal/emulator/src/com/intellij/terminal/emulator/impl/ghostty/OscCustomCommandSniffer.kt#L32).
The [emulator](https://github.com/JetBrains/intellij-community/blob/650ce34fddae6f8d50e99d0419139d39f9df052f/plugins/terminal/emulator/src/com/intellij/terminal/emulator/impl/ghostty/GhosttyTerminalEmulator.kt#L582)
delivers preceding terminal output, the semantic callback, then subsequent
output; its [session](https://github.com/JetBrains/intellij-community/blob/650ce34fddae6f8d50e99d0419139d39f9df052f/plugins/terminal/frontend/src/com/intellij/terminal/frontend/session/ghostty/GhosttyTerminalSession.kt#L290)
connects its existing shell controller synchronously. These are pinned upstream
master sources retrieved on October 3, not evidence about the installed IDEA
2026.2 target. Source inspection indicates that the same arrangement can wrap
our `parserFactory`, using the producer factory's `TerminalShellIntegrationContext`
to capture serialized line anchors at callbacks; D06 requires an executable
proof. KetraTerm need not
interpret JetBrains OSC 1341 or replace their shell integration to support it.

## Findings

### D01 — P2: `Running` is published before transport readiness

**Reproduced defect.** [Session startup](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L448)
publishes `Running` before resize and `connector.start`. Its
[documented meaning](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSessionState.kt#L35)
is that the connector has started and input is accepted. Ordinary input admission
uses that state, while bulk output separately requires `connectorStarted`.
An observer of `Running` can write a key before startup or fail-close a healthy
session by pasting/replacing text. Three deterministic regressions reproduce it.

Give start-once claiming, observable readiness, normal input and the writer one
coherent contract. Account for synchronous output, replies, shell readiness,
failure and reentrant closure during `connector.start`. Simply rejecting input
after publishing `Running` would contradict its contract. The existing
reentrant-close case that expects zero starts when observing `Running` relies
on early publication; reconcile it while preserving the real invariant that a
closed connector is never subsequently started.

### D02 — P2: public configuration crosses its effective owners

**Source-confirmed boundary problem.**
[SwingSettings](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/settings/SwingSettings.kt#L152)
publishes `scrollbackLines`, `shellRequestResizeWindow` and
`shellRequestWindowManipulation`, but Swing never reads them. Products apply
scrollback during core creation and window permissions through host policy.
Reloading those advertised UI settings cannot produce their described effect.

[TerminalConfig](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/config/TerminalConfig.kt#L89)
publishes a 31-field product preferences schema, and its
[TOML manager](../../ketraterm-workspace/src/main/kotlin/io/github/ketraterm/workspace/config/TerminalWorkspaceConfigManager.kt#L423)
chooses KetraTerm product paths. The workspace tab runtime consumes neither.
Standalone owns that schema and persistence; the plugin shares defaults/bounds.
Adding a desktop preference should not change workspace ABI.

Remove the three ineffective Swing properties before freeze. Move product
preferences/persistence to standalone, placing genuinely shared bounds with
their existing owners. Preserve implemented bind-time width, palette, cursor
and paste policy semantics. No generic configuration module is warranted.

### D03 — P2: growing settings have no economical construction/update route

**Pre-freeze design decision.** `SwingSettings` has 40 constructor properties;
[SwingHostServices](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingHostServices.kt#L120)
has 14. Their constructor, default-call, `copy` and component shapes are fixed
by the current compatibility rules. EDT attachment methods suit diagnostics or
view lifetimes, but a new paint/geometry option needs to remain in the same
immutable settings snapshot. Adding separate setters creates another authority.
Java cannot selectively supply a late font resolver without preceding arguments;
current Java consumers demonstrate prefixes rather than this real host journey.

[PtyOptions](../../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyOptions.kt#L65)
and workspace open options also propagate launch and terminal-assembly options
through constructor/copy shapes. Rehearse one real option through these owners
before choosing which construction surfaces need change. This revisits A02's
deliberate fixed-shape choice, not an unfixed A02 linkage regression.

Prototype stable named construction and copy/update operations for genuinely
growing configuration, retaining one validated immutable runtime snapshot. A
concrete configuration builder with Kotlin conveniences and selective Java
methods is a candidate, not a mandate for every record. Do not group unrelated
options merely to shorten constructors. Kotlin's
[compatibility guidance](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#avoid-using-data-classes-in-your-api)
explains why adding a defaulted data-class property does not preserve its
generated binary entry points.

### D04 — P2: consumers receive producer mutation authority

**API ownership decision.**
[TerminalSession](../../ketraterm-session/src/main/kotlin/io/github/ketraterm/session/TerminalSession.kt#L165)
exposes its mutable render publisher and full shell state. Ordinary views and
completion consumers can call `updateAndPublish`, `record*` or `clear`, although
session synchronization and one selected shell producer own those publications.
Production consumers query/copy/listen; producers already retain their own state.
No current corruption is claimed.

Prefer consumer access that cannot publish, without another module or a chain
of wrappers. A shell read-only role can be implemented by its existing owner.
For frames, an inline session read operation with narrow non-inline
acquire/release bridges can retain the concrete publisher privately and avoid
another view object. The standalone publisher remains mutable for its actual
owner. Evaluate this together with D05: a naive callback interface must not
introduce per-frame capturing-lambda allocation. Keep borrowed primitive arrays
and their lifetime contract; this is not a demand for immutable array objects
or protection from every deliberately invalid cast.

### D05 — P2: inline frame reads freeze buffer indexing into client binaries

**Implementation-evolution risk.**
[TerminalRenderPublisher.readCurrent](../../ketraterm-render-cache/src/main/kotlin/io/github/ketraterm/render/cache/TerminalRenderPublisher.kt#L150)
calls non-inline lease bookkeeping, but its copied body still indexes the
`@PublishedApi` buffer array using an integer lease. Previously retained clients
also commit older lock/count/index bookkeeping. Compatibility passes today;
this does not reopen the corrected A07 lease behavior. Freezing now makes these
implementation representations permanent obligations.

Keep only the callback invocation and `try/finally` inline. Investigate
non-inline acquire/release using an existing cache reference as the lease
witness, hiding buffer count, indexing, locks and counters. Preserve non-local
return, failure release and concurrent readers. Do not allocate a lease per
read. Deliberately replace affected development clients only after this design
is implemented and locally measured; do not silently preserve old internals as
the stable design. Development baseline changes must remain explicit.

### D06 — P2: external consumers do not demonstrate the complete host journey

**Verification gap.** The external Swing fixture injects an already-created
editing `StateFlow`; the host shell test records metadata after a complete
chunk. Neither exercises proprietary markers between earlier and later bytes
in the same chunk. The
[native-popup fixture](../../ketraterm-testkit/src/consumerTest/ui-swing-host/src/main/kotlin/consumer/KotlinConsumer.kt#L65)
disables automatic suggestions and calls its recording target directly. These
are useful signature checks, not proof of straightforward native integration.

Build one runnable external host example with its own connector, ordered shell
decoder/model, settings, clipboard, provider and popup/controller. Route user
copy/paste and policy-controlled OSC 52 callbacks through the host's clipboard
service without confusing their two integration paths. Exercise
multiple markers per chunk, all byte splits, EOF/reset, stable line anchors,
editing/readiness, prompt decorations/navigation, typing, cancellation, stale
acceptance, focus, resize and disposal. Verify that it needs no workspace,
standalone or optional OSC dependency. Also retain a minimal default assembly
and a headless parser/core example. Compile the actual examples for supported
Kotlin and representative Java use. Add a shared operation only if the example
reveals repeated library-owned work; do not hide legitimate host ownership.

### D07 — P2/P3: optional reusable presentation embeds product policy

**Host reuse decision and source boundary debt.** The
[view snapshot](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/suggestion/SwingShellSuggestionView.kt#L61)
rejects more than eight visible rows. The reusable coordinator fixes debounce
and minimum-character triggering internally. A fully host-owned controller can
choose its own policy, but reusing ours commits the host to those defaults.

The host-neutral
[completion adapter](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingCompletionSuggestionProvider.kt#L90)
strips `intellij-` and hardcodes plugin source labels. The reusable
[search bar](../../ketraterm-ui-swing-host/src/main/kotlin/io/github/ketraterm/ui/swing/host/SwingTerminalSearchBar.kt#L297)
claims to refresh host colors but reapplies fixed dark constants. Hosts can
replace it, but cannot supply styling while reusing it as requested.

Use D06 to settle the narrow policies hosts actually need to override. Keep
bounded lists and default UX, separate plugin label knowledge at composition,
and allow prepared host styling for reusable chrome. Avoid a universal option
bag, popup framework or exposing child component internals. Any new policy must
use D03's chosen construction route rather than another parallel authority.

### D08 — P3: the packed mode contract implies more growth than it supports

**Contract clarification.**
[TerminalModeReader](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/api/TerminalModeReader.kt#L93)
describes a word containing all active modes, but published mode fields and
internal xterm key-resource packing leave only bits 19 and 63 free. The public
data snapshot is also a fixed subset. Mouse fields encode enum ordinals; the
existing numeric meanings are compatibility commitments, already recognized
by the compatibility rules. There is no current mode corruption.

Define the existing word/snapshot as its named published subset, reserve its
numeric meanings, and preserve coherent primitive input reads. A genuinely new
state family may gain a focused capability when implemented. Do not prebuild a
mode registry, widen every input event or allocate full snapshots in hot paths.

### D09 — P3: the primary Swing example uses nonexistent APIs

**Compiler-reproduced documentation defect.** The
[usage example](../../ketraterm-ui-swing/README.md#how-to-use)
supplies nonexistent `fontFamily`/`fontSize` arguments. Current settings require
`Font`; the imported `TerminalTheme` and its resolved palette are valid. The
settings KDoc also claims system fallback fonts are
disabled by default, while the constructor enables them. Correct the example
and defaults documentation, and compile the actual documented example,
including its EDT and view/session ownership contract. Existing external
consumer compilation does not test this snippet.

### D10 — P3: adapter flag reads construct complete mode snapshots

**Localized source-level performance debt.**
[Line feed](../../ketraterm-host/src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt#L168)
and Kitty flag application request a full snapshot that the factory-created
[core constructs](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/model/TerminalModes.kt#L218)
to inspect one field. An external core could return a cached object.
Primitive `TerminalInputState` helpers already exist. Use the coherent packed
read and those helpers instead of introducing another API. The snapshot
construction is source-confirmed; escaping allocation and latency have not been
measured and JVM elimination must not be assumed either way. This is independent
of the per-frame performance gate and is not a reason for a broad adapter rewrite.

## Work order and completion criteria

1. **Startup correctness — D01.** Align readiness and input; pass all three new
   cases plus startup replies/readiness, closure and repeat-start coverage.
2. **Adoption proof — D06/D09.** Establish a real reference host and compiling
   documentation before deciding convenience APIs. Write down actual assembly
   responsibilities and boilerplate revealed by that example.
3. **Configuration — D02/D03.** Set effective owners and evolving construction
   together, then migrate both products once. Prove selective Kotlin/Java use,
   immutable copy/update and a concrete additive option with retained clients.
4. **Publication ownership — D04/D05.** Narrow consumer authority while removing
   incidental inline ABI. Keep borrowed reads, release semantics and allocation
   behavior; validate old/new readers against the deliberately chosen baseline.
5. **Reusable host UX — D07.** Apply only sample-proven policy/style overrides;
   remove plugin knowledge from shared presentation.
6. **Mode contract and local cleanup — D08/D10.** Clarify the bounded contract
   and use existing primitive reads; no new general extension machinery.
7. **Review the resulting public surface.** Deliberately refresh development
   baselines after approved changes. Freeze only when the reference hosts work,
   ownership is explicit, configuration can grow without replacing old calls,
   new semantic families can be optional, and the tested lifecycle contracts are
   coherent. G02/G03 remain separate subsequent decisions.

Design choices do not earn tests that merely enforce a preferred class layout.
Require behavioral consumer proofs for the chosen design; retain failing
correctness regressions for actual defects. No baseline refresh is justified
while these choices remain unsettled.

## Validation

Before adding regressions, the root `test`, `checkKotlinAbi`, `spotlessCheck` and
`:ketraterm-testkit:publishedConsumerTest` command passed with the cached Gradle
9.6.1 distribution. Published source consumers passed both metadata modes and
Kotlin compilers; the retained-client suite passed 52 upgrades and two deliberate
linkage-failure controls. This verifies declared development baselines.

The added `TerminalSessionTest` parameterized regression
`input from a running state observer cannot reach an unstarted connector`
covers key, paste and replacement with a shared deterministic scheduler. All
three cases fail on the reviewed implementation. The full owning suite reports
301 tests and exactly these three failures; ABI and formatting checks pass.
The assertions require the documented readiness and successful input contract,
not a particular queueing implementation. No production correction is included.

The unchanged Swing README Kotlin block was copied temporarily into the prepared
published-consumer build and compiled against staged Gradle-metadata artifacts
with Kotlin 2.4.20. Compilation failed with `NAMED_PARAMETER_NOT_FOUND` for
`fontFamily` and `fontSize`. The temporary source was removed and the ordinary
consumer compilation restored. No Kotlin-snippet compiler framework or permanent
documentation regression was added; D09 closure requires the actual example to
become continuously compiled.

The separate plugin suite, native opt-in PTY tests, installed products and
performance budgets were not rerun for this review. Source-only/design findings
do not claim new fault injection, allocation measurements or completed adoption
samples. Historical A/M review evidence and closures remain intact.
