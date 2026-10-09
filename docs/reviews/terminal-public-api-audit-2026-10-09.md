# Public API ergonomics audit — 2026-10-09

Reviewed revision: `dadb708cd4bfb8832921579292cff618472a19f5`.

This report consolidates the retained findings from both public API review passes.
It evaluates concrete consumer workflows, custom implementations, allocation-facing
contracts, maintainability, and composition. It does not establish implementation
correctness, measured performance, or an exhaustive absence of other issues.

The [gap map](../terminal-feature-gap-map.md#public-api-ergonomics-review) owns
follow-up status. This report preserves the evidence, impact, alternatives, and
acceptance criteria at the reviewed revision; it is not a second capability map.

## Scope and evidence

The review used public Kotlin declarations and public ABI snapshots across the
15 supported library modules: protocol, parser, core, host, input, completion,
completion-host, render-api, render-cache, transport-api, session,
shell-integration, ui-swing, ui-swing-host, and pty. Product-only workspace,
completion-persistence, app, and IntelliJ implementation surfaces were outside
the supported external library boundary.

Implementation bodies, runtime behavior, and benchmarks were not used to prove
these findings. Outdated repository documentation and examples were not treated
as requirements or evidence of missing capabilities. Existing feature/gap maps
were consulted when recording follow-ups to avoid duplicate ownership entries.
No production changes or executable regressions accompany this report.

P2 identifies meaningful friction in a concrete supported consumer workflow.
P3 identifies narrower ergonomics with an available workaround. Neither means
that a broad redesign or a new abstraction layer is required.

| Finding | Priority | Owner | Consumer impact |
| --- | --- | --- | --- |
| [API01](#api01-selected-text-without-clipboard) | P2 | UI | Read selected text for host actions without invoking clipboard behavior. |
| [API02](#api02-independent-replay-retention-control) | P2 | Completion | Retain ranking evidence while disabling or restricting plaintext replay. |
| [API03](#api03-constructible-packed-input-modes) | P2 | Core | Feed the stock encoder from an independent input-state implementation. |
| [API04](#api04-preceding-arguments-in-completion-context) | P2 | Completion | Resolve dynamic values using preceding arguments without parsing again. |
| [API05](#api05-pixel-to-cell-hit-testing) | P3 | UI | Implement pointer actions without scanning cells or duplicating geometry. |
| [API06](#api06-completion-context-resolution-for-custom-engines) | P3 | Completion | Reuse stock sources from a host-owned completion engine. |
| [API07](#api07-capacity-discovery-for-direct-cluster-reads) | P3 | Core | Size reusable storage for a direct single-cell cluster read. |

## API01 Selected text without clipboard

**Priority: P2.**

**Public evidence.** `SwingTerminal` exposes
[`currentSelectionRange`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1460)
and
[`copySelectionToClipboard`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L2220),
but its [public surface](../../ketraterm-ui-swing/api/ketraterm-ui-swing.api)
has no clipboard-independent selected-text accessor.

**Consumer workflow.** A host implements Find Selection, Send Selection to an
editor, or a custom action receiving the exact selected text. Coordinates alone
do not provide the text with the terminal's selection semantics.

**Friction and workaround.** The host must route through clipboard-oriented
behavior or reconstruct text from selection coordinates and render data. The
latter duplicates decisions about soft wrapping, block selection, wide cells,
and grapheme clusters. Existing programmatic selection support remains useful;
this finding concerns extraction, not selection assignment or observation.

**Smallest direction.** Expose selected text using the same extraction semantics
as clipboard copy. Define the empty-selection result and existing UI-thread
requirement. A returned string is appropriate for this user action; a new stream
or selection model is unnecessary.

**Acceptance criteria.** External callers can obtain text without a clipboard
read/write. Cover linear and block selection, soft wraps versus hard breaks,
wide/cluster text, empty selection, and stale or clipped ranges. Verify parity
with clipboard extraction without duplicating that extraction implementation.

**Comparison.** xterm.js exposes
[`getSelection()` separately from selection positions](https://xtermjs.org/docs/api/terminal/classes/terminal/#getselection).
The host-action workflow, rather than API parity alone, motivates this finding.

## API02 Independent replay retention control

**Priority: P2.**

**Public evidence.**
[`TerminalCompletionLearningStore`](../../ketraterm-completion/src/main/kotlin/io/github/ketraterm/completion/api/TerminalCompletionLearningStore.kt#L40)
accepts capacity at construction and exposes command-result recording, feedback,
snapshot merging, snapshot reads, and clearing. It has no caller-selectable
plaintext retention policy.
[`TerminalCompletionReplayPolicy`](../../ketraterm-completion/src/main/kotlin/io/github/ketraterm/completion/api/TerminalCompletionReplayPolicy.kt#L29)
is a fixed object with an `allowsPlaintext` query, not an injectable store policy.

**Consumer workflow.** An embedded host wants learned ranking while retaining no
plaintext replay commands, or wants to reject project-specific command patterns
in addition to the library's built-in filter.

**Friction and workaround.** Filtering events before recording also discards
their ranking evidence. Filtering exported snapshots controls downstream
persistence, not retention in the original store. Disabling learning entirely is
possible but unnecessarily removes the desired ranking behavior. This is a
consumer-policy gap, not a claim that the current filter leaks a particular secret.

**Smallest direction.** Add an explicit replay-retention choice, including an
opaque-ranking-only mode. If a host predicate is provided, it should be able to
restrict the built-in admission decision without bypassing structural bounds.
Keep the policy in the learning owner, independent of persistence.

**Acceptance criteria.** With replay disabled, successful command outcomes still
affect ranking evidence but create no replay rows. Apply the same admission rule
to imported snapshots and every replay-producing entry point. Preserve bounded
storage, existing default behavior, and deterministic rejection by host policy.

## API03 Constructible packed input modes

**Priority: P2.**

**Public evidence.**
[`TerminalInputState`](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/api/TerminalInputState.kt#L32)
requires one coherent packed `Long`. Its companion exposes `keyModifierOption`
and `keyFormatOption` decoders. The
[`TerminalModeBits` public surface](../../ketraterm-core/api/ketraterm-core.api#L121)
exports packing metadata for OTHER_KEYS, mouse, Kitty, and ordinary boolean
modes, but no corresponding semantic packers or packing metadata for the other
modifier/format resources. `withPackedValue` requires the caller to supply the
missing mask, shift, and representation.

**Consumer workflow.** An independent core or remote mode adapter implements
`TerminalInputState` and reuses KetraTerm's stock input encoder.

**Friction and workaround.** Such a producer can construct many modes from public
constants, but cannot fully construct the modifier/format state through the
published vocabulary. It must reproduce unpublished packing or mirror modes in
a stock terminal buffer. The latter introduces unrelated storage and a second
representation of the same state. `TerminalModeController` mutation methods
do not provide a standalone packed-state construction operation.

**Smallest direction.** Add primitive semantic helpers such as
`withKeyModifierOption(bits, resource, value)` and
`withKeyFormatOption(bits, resource, value)` beside the decoders. Avoid another
mode model, registry, or factory.

**Acceptance criteria.** An external input-state implementation can construct
every supported modifier/format resource using public APIs alone. Verify
pack/decode round trips, default values, explicit disable where supported,
invalid resource/value handling, and preservation of unrelated bits. Retain the
single-word snapshot and allocation-minimal encoder boundary.

## API04 Preceding arguments in completion context

**Priority: P2.**

**Public evidence.** The complete
[`TerminalCompletionContext` surface](../../ketraterm-completion/api/ketraterm-completion.api#L62)
exposes active position, option, prefix, expected domain, replacement offsets,
and command-spec context. It exposes neither preceding argument values nor a
parsed token view. Providers such as
[`valueDomain`](../../ketraterm-completion/src/main/kotlin/io/github/ketraterm/completion/api/TerminalCompletionSources.kt#L145)
receive that context and a request containing the raw command line.

**Consumer workflow.** For `kubectl --context prod --namespace <cursor>`, a
namespace source needs the preceding context value `prod`. Likewise, a
repository-aware completion source may need a preceding directory argument.

**Friction and workaround.** The provider must tokenize and interpret the raw
request again, including quoting, escaping, separators, and repeated options.
This duplicates parsing knowledge even when the provider uses the standard
completion engine. `activeOption` describes the current position; it does not
answer which values preceded it.

**Smallest direction.** Expose a bounded, read-only argument view or query through
the existing context so providers can share the engine's parse. Do not expose a
mutable internal AST or require a separate provider-owned parser.

**Acceptance criteria.** A source can select a dataset from preceding option and
positional values without reparsing. Define ordering and repeated-option access,
command boundaries, and quoting/escaping treatment. Cover incomplete active
tokens and ensure asynchronous sources cannot observe reused mutable storage.

**Comparison.** JLine completers receive a
[`ParsedLine` exposing individual words](https://www.javadoc.io/static/org.jline/jline/4.0.0/org/jline/reader/ParsedLine.html).
That is precedent for sharing parsed context, not a requirement to copy JLine's
type hierarchy or implement shell execution semantics.

## API05 Pixel-to-cell hit testing

**Priority: P3.**

**Public evidence.**
[`SwingTerminalContextMenuRequest`](../../ketraterm-ui-swing/api/ketraterm-ui-swing.api#L359)
provides component-local mouse coordinates. `SwingTerminal` exposes
[`copyCellBounds`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt#L1576)
for logical-cell-to-pixel mapping, but no inverse cell hit-test query.
`commandRecordAt` identifies a command record, not an arbitrary cell.

**Consumer workflow.** A host adds Inspect Character, Copy Line Under Pointer,
custom word selection, or cell-aware hover inspection.

**Friction and workaround.** Consumers can scan cell rectangles using one reusable
`Rectangle`; the operation is not impossible. They must nevertheless own that
search or reproduce geometry involving padding, prompt presentation, scrolling,
and bidi ordering. Simple pixel division is not a general replacement. No
measured latency or allocation regression is claimed.

**Smallest direction.** Add an inverse query consistent with `copyCellBounds`,
using caller-owned storage or a primitive result. Specify logical coordinates
and distinguish viewport rows from absolute retained rows. Reject locations
outside terminal content without allocating per pointer event.

**Acceptance criteria.** Verify correspondence with forward cell bounds across
scrolling, fractional scaling, gutters/dividers, bidi and wide cells, clipped
content, and points outside the grid. Define spacer-cell behavior explicitly.

## API06 Completion context resolution for custom engines

**Priority: P3.**

**Public evidence.** `TerminalCompletionEngine` and `TerminalCompletionSource`
are public extension contracts. Sources require a `TerminalCompletionContext`,
but that class has no public constructor or public resolver in the
[completion API surface](../../ketraterm-completion/api/ketraterm-completion.api).
[`TerminalCompletionEngines.fromSources`](../../ketraterm-completion/src/main/kotlin/io/github/ketraterm/completion/api/TerminalCompletionEngines.kt#L46)
provides the stock merged engine rather than standalone context resolution.

**Consumer workflow.** A host supplies its own scheduling or ranking engine but
wants to reuse stock completion sources and candidate projection helpers with
the same syntax interpretation.

**Friction and workaround.** The host can retain the stock engine and transform
its results, or make a source participate in a stock-engine invocation to obtain
a context. Those are workable choices, but coupling context acquisition to the
merged engine is awkward when the consumer specifically replaces that engine.
This is narrower than API04, which affects ordinary stock-engine providers too.

**Smallest direction.** Expose the existing context-resolution capability as a
focused public operation if supporting this composition. Prefer an invariant-
preserving resolver over a constructor accepting inconsistent derived fields.
Coordinate this work with API04 to avoid two representations of parsed input.

**Acceptance criteria.** An external engine can obtain a valid context and invoke
a stock source without instantiating the stock merged engine or importing
internal packages. Use the same command catalog and shell syntax rules; cover
empty/incomplete requests, replacement offsets, and asynchronous context lifetime.

## API07 Capacity discovery for direct cluster reads

**Priority: P3; narrow convenience gap.**

**Public evidence.**
[`TerminalLine.readCluster`](../../ketraterm-core/src/main/kotlin/io/github/ketraterm/core/api/TerminalLine.kt#L77)
requires a caller-owned `IntArray`, but the line API supplies no length query or
capacity-discovery operation. There is no universal public maximum for directly
written clusters. The render API already offers complete cluster data through
`TerminalRenderFrame.copyLine` and `TerminalRenderClusterDataSink`.

**Consumer workflow.** A low-level reader needs the complete cluster in one cell
and wants to grow a reusable buffer only when necessary.

**Friction and workaround.** The caller can use the existing render-frame sink
path, or grow/retry a direct read when its buffer is too small. Complete reads
are already possible. This finding only concerns the ergonomics of the direct
single-cell API; it does not request broader Unicode support or a new parser
retention limit.

**Smallest direction.** Consider a length/capacity query or equivalent primitive
read contract on the existing boundary. Preserve the owning read lease or
serialization across sizing and copying. Do not add a second cluster model.

**Acceptance criteria.** Cover non-cluster cells, exact and insufficient capacity,
directly written clusters exceeding an initial scratch size, and safe reuse
within the existing serialization boundary. Preserve allocation-free copies
when the caller's destination is sufficient.

## Considered but not promoted

These notes preserve review corrections and uncertainty. They do not create
additional implementation TODOs.

| Concern | Disposition and reason |
| --- | --- |
| PTY factory composition | Withdraw the earlier P2. The public [PtyConnector constructor](../../ketraterm-pty/src/main/kotlin/io/github/ketraterm/pty/PtyConnector.kt#L91) accepts a configured Pty4J process and connector tuning; callers can then use custom session construction. Narrower convenience factories do not block the advanced workflow. |
| View-local read-only input | Plausible consumer need, not confirmed here. A view-specific input gate would differ from suppressing all session input, but public signatures alone do not settle inherited Swing disabling behavior. Validate the live selectable-view/host-automation workflow before adding a setting. xterm.js offers a comparable [disableStdin option](https://xtermjs.org/docs/api/terminal/interfaces/iterminaloptions/#optional-disablestdin). |
| Seeding the reusable search bar | Possible P3 helper ergonomics. Its public methods have no query argument, but terminal-level search already exists; establish whether composition with current search state is sufficient before adding another control. |
| Feedback identity in convenience projection inputs | Possible P3. Rich candidates already carry feedback identity; lower-level customization remains available. Filtering/limiting/reordering means positional zipping is not a universally safe workaround, but no necessary workflow was established as blocked. |
| Java checked-I/O exceptions on connectors | Minor interoperability asymmetry. Java implementations can wrap checked I/O exceptions conventionally; changing the throws contract has compatibility costs and is not required by this audit. |
| Output drain/flush receipt | A possible future lifecycle requirement, not a demonstrated missing requirement for the reviewed workflows. Admission and transport completion should not be conflated. |
| Data-class evolution and custom OSC ownership | Compatibility/design considerations rather than independently justified defects. No blanket conversion of public data classes or new extension framework is requested. |
| Render-reader synchronization and detector replacement | Earlier concerns were not established by public signatures. Custom readers have override seams, and constructor-owned services are a valid design. |
| Palette ambiguity and documentation/example mismatches | Excluded from the issue list. Stale prose does not establish a missing consumer capability. |

## Follow-up discipline

Implement findings within their existing owners. Prefer small operations on the
current boundaries over factories, wrappers, or interfaces introduced solely for
future flexibility. API03 can remain entirely primitive; API04 and API06 should
share context parsing; API01 and API05 should reuse existing selection and
geometry semantics.

When implementing a finding, add focused consumer/semantic coverage, update the
public ABI baseline as appropriate, and record actual verification in the gap
map. The acceptance criteria above are proposed checks, not tests already run.
