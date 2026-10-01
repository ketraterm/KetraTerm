# URI highlighting grouping and action lifetime — 2026-10-01

Stage 4 on `fix/uri-highlighting`, following Stage 3 commit `00fd401a`.
Scope and subsequent gates remain in the
[canonical repair map](../terminal-feature-gap-map.md#uri-highlighting-staged-repair).

## Correction after interactive review

The original Stage 4 assertions below were too broad: they deliberately hovered
both agy's wrapped URL and its separate authentication caption. The user rejected
that behavior. OSC 8 identity remains the destination/action identity; it no longer
means that every disconnected displayed occurrence must hover together.

Shared interaction and painting now consume one reusable primitive hover
projection. For OSC 8, the pointed segment connects to overlapping segments on
adjacent rows and actual terminal soft-wrap continuations, including artificial
wide-character wrap padding. Separate runs on the same row and occurrences
separated by nonlinked rows remain separate. Detector IDs already identify a
specific discovered occurrence and retain their multi-segment grouping. This
visible-occurrence policy also applies to anonymous OSC 8 runs; overwritten
content that separates a run into disconnected islands separates its hover.
No host-registry identity or complete activation target changes.

The agy replay now expects five connected URL fragments or the one caption,
never both. Painter regressions verify that even cells sharing one OSC 8 ID can
paint different hover states, including shaped text. Mouse movement between
such occurrences reprojects/repaints the old and new segments; a release over a
different visible occurrence cannot activate the pressed occurrence.

Fixture review also found that the old sanitizer collapsed every explicit ID to
one constant without validating the original equality partition. The original
capture was deleted, so its identity claim cannot be recovered from that fixture.
A fresh isolated, signed-out ConPTY capture independently confirmed that the
installed agy emits one explicit ID/destination across all six openings. Its
metadata is recorded under ignored `build/uri-regression/agy-identity-metadata.json`;
no OAuth URI or private ID was printed and no sign-in was completed. The existing
staged ANSI fixture remains unchanged. Future sanitization must preserve distinct
IDs as distinct inert replacements.

The historical test counts and allocation figures below describe the previous
implementation, not validation of this correction. Current validation belongs in
the [regression review](uri-highlighting-regressions-2026-10-01.md).

## Original Stage 4 findings and implementation

The host registry already assigns one positive numeric identity to an explicit
OSC 8 ID/destination pair, and a distinct identity to each anonymous opening.
The retained detector index likewise assigns stable negative occurrence IDs.
The defect was in Swing's adjacency-based hover bounds, which discarded that
identity across hard breaks and gaps. No registry or parser change was needed.

This follows the [OSC 8 identity specification](https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda#hover-underlining-and-the-id-parameter):
explicit identity groups matching destinations; anonymous runs retain their
existing independent identities. Equality of destination alone never groups
separate occurrences.

The controller now projects the hovered identity into reusable primitive
row/start/end triples. Retaining the previous projection allows precise repaint
requests for erased or moved fragments as well as the new visible group.
Painting compares semantic IDs directly; the old hover-bound parameters were
removed from the painter chain and all callers together. Moving within a group
performs a hit test without rescanning or repainting it. A frame refresh or
change of target scans visible cells only, independently of history size.

The wrap-padding regression exposed an additional issue: core correctly carries
the current pen's hyperlink ID on artificial wide-character wrap padding.
Shared Swing cell policy now excludes `WRAP_PADDING` from interaction and link
decoration while preserving authored spaces and wide trailing cells. Core's
stored pen/cell state remains untouched.

Menus now capture the resolved action and complete optional URI at creation.
Later output, scrolling, eviction, reset or rebinding cannot resolve that menu
against a different projection or session. Detected results supplying `uri`
automatically expose the existing host Copy Link item. Menu construction may
allocate; the frame and hover paths do not construct actions or URI strings.

## Original correctness evidence (superseded grouping expectations)

- The unchanged 7,502-byte agy capture is replayed at 176×32 with chunk sizes
  1, 7 and whole input. Hovering any of the five URL fragments or the separate
  authentication caption selects all six visible segments, including authored
  padding. Every fragment opens the complete 704-character destination, and
  moving within that group adds no repaint requests.
- Real OSC 8 byte streams cover different explicit IDs, one explicit ID with
  different destinations, repeated anonymous destinations, anonymous runs across
  hard breaks, partial overwrite, erasure, Unicode, artificial wrap padding,
  clipped viewports, history, alternate buffers, eviction and resize/reflow.
- Retained detector tests preserve multirow grouping through frame carry and
  scrolling without merging separate matches sharing an action.
- Painter tests cover same-ID separated segments, different-ID isolation,
  artificial padding, run boundaries and the existing text paths.
- Controlled Swing/session tests capture menus before detected-text replacement
  and rebinding, and before OSC 8 registry reset/replacement. Opening and copying
  the captured target still use the original destination.

Validation commands:

```text
.\gradlew.bat spotlessApply :ketraterm-ui-swing:test :ketraterm-app:compileKotlin :ketraterm-benchmarks:jmhJar
.\gradlew.bat -p ketraterm-intellij-plugin test verifyPluginProjectConfiguration
graphify update .
```

Swing: 1,042 tests, zero failures/skips. IntelliJ: 225 tests, zero failures/skips.
Standalone compilation and JMH harness generation pass. Plugin configuration
verification retains the existing explicit-coroutine-dependency warning.
Graphify updates successfully, with its existing partial-parser warnings for
three unrelated Kotlin files; Kotlin compilation succeeds.

## Original prepared-path allocation measurements

JMH 1.37 on JBR 25.0.4.1+1-b610.67, one fork, two one-second warmups and
three one-second measurements, with no concurrent builds or Graphify work.
`ThreadMXBean` measures allocations on the benchmark owner thread, separately
from the GC profiler's background activity. All three measured iterations of
all nine configurations recorded exactly **zero owner-thread bytes**.

| Workload | Grid | Retained rows | Mean µs/op |
|---|---|---:|---:|
| Hover group projection, modifiers and refresh | 80×24 | — | 5.34 |
| Hover group projection, modifiers and refresh | 160×48 | — | 18.60 |
| Hover group projection, modifiers and refresh | 240×48 | — | 29.49 |
| Prepared history projection | 80×24 | 1,000 | 7.97 |
| Prepared history projection | 80×24 | 10,000 | 16.00 |
| Prepared history projection | 160×48 | 1,000 | 20.26 |
| Prepared history projection | 160×48 | 10,000 | 31.66 |
| Prepared history projection | 240×48 | 1,000 | 21.33 |
| Prepared history projection | 240×48 | 10,000 | 33.80 |

The new hover workload deliberately alternates three groups with separated
single-cell segments throughout the viewport. Each operation changes the hovered
group, toggles activation twice, and refreshes the unchanged projection. The host
records repaint requests without calling AWT. The existing history workload
copies frames, scrolls through prepared results and resolves retained actions.
These short local measurements are evidence for those bounded primitive paths,
not timing thresholds, comparative speedup claims or whole-Swing-frame results.
Storage growth, menu creation, discovery, AWT repaint scheduling, raster painting
and provider callbacks are outside these measurements.

Reproduce after building the JMH jar:

```text
java -jar ketraterm-benchmarks/build/libs/ketraterm-benchmarks-0.3.0-SNAPSHOT-jmh.jar "TerminalHyperlink(Hover|Projection)Benchmark.countPreparedAllocations" -f 1 -wi 2 -w 1s -i 3 -r 1s -prof gc -rf json -rff build/uri-stage4/prepared-thread-allocations.json
```

Local raw JSON and log are under ignored `build/uri-stage4/`. JMH's existing
Unsafe deprecation warning comes from its harness, not the production changes.

Native agy/IDE visual verification remains the user's review step. This stage
does not claim IntelliJ provider gesture/style parity or whole-frame allocation
coverage; those gates remain in Stages 6 and 7. Changelogs remain consolidated
for the completed user-visible repair rather than gaining a per-stage entry.
