# Terminal Conformance Testing

KetraTerm verifies terminal behavior with complementary deterministic test
strategies. No single external emulator is treated as a universal oracle:
terminal implementations legitimately differ in policy areas such as
width-changing resize and logical-line reflow.

## Differential campaign

The xterm.js campaign replays generated protocol streams through KetraTerm and
the version-pinned `@xterm/headless` oracle. Comparisons are restricted to state
that both implementations expose. Intentional policy differences must declare
their rationale and exact allowed mismatch paths.

```bash
./gradlew :ketraterm-testkit:xtermDifferentialSmokeTest
./gradlew :ketraterm-testkit:xtermDifferentialNightlyTest
```

Pull requests run 100 deterministic cases. The scheduled campaign runs 100,000
cases in four seed-range shards.

## Resize and reflow campaign

The resize campaign constructs mixed-width Unicode state and repeatedly changes
the viewport width and height. It verifies exact grapheme preservation, cursor
bounds, dimensions, render-cell flags, and wide-cell adjacency. This is an
invariant campaign rather than an xterm.js comparison because KetraTerm reflows
logical lines while headless xterm.js retains physical rows.

```bash
./gradlew :ketraterm-testkit:resizeReflowInvariantSmokeTest
./gradlew :ketraterm-testkit:resizeReflowInvariantNightlyTest
```

## Independent grid-physics model

The grid-physics campaign executes each generated operation against the real
parser-to-core pipeline and a deliberately small independent model. It covers:

- ASCII, combining clusters, CJK characters, and width-two emoji;
- deferred wrap, DECAWM, CR, LF, BS, CUP, CUF, and CUB;
- DECSTBM, DECSLRM, DECLRMM, and DECOM;
- RI, IL, DL, SU, and SD;
- full-screen and partial-region scrolling, including scrollback admission;
- wide occupants crossing horizontal line-mutation slice boundaries.

The comparison includes cell and cluster contents, wide spans, cursor position,
mode state, soft-wrap markers, scrolling, and retained history.

```bash
./gradlew :ketraterm-testkit:cursorWrapModelSmokeTest
./gradlew :ketraterm-testkit:cursorWrapModelNightlyTest
```

Pull requests run 100 cases. Nightly CI runs 25,000 cases in four deterministic
ranges of 6,250.

## Reproducing failures

Every campaign uses a fixed base seed plus a global case index. Select an exact
range with the corresponding `StartIndex` and `Cases` Gradle properties:

```bash
./gradlew :ketraterm-testkit:cursorWrapModelNightlyTest \
  -PcursorWrapStartIndex=12500 \
  -PcursorWrapCases=6250
```

Campaign manifests record the implementation or oracle version where
applicable, base seed, case range, commit SHA, comparison scope, and status.
Failures are automatically minimized and written beneath the relevant
`ketraterm-testkit/build/reports` campaign directory. CI uploads manifests for
all runs and retains minimized operation streams plus JUnit reports on failure.

## Wide-span mutation contract

Horizontal-margin IL and DL operate on rectangular slices, but a width-two
occupant remains indivisible. Before copying or clearing a destination slice,
core annihilates any occupant crossing either boundary. A source slice never
copies a leader without its trailing cell or a trailing cell without its
leader. This rule prevents orphaned wide cells even when margins change between
writing the glyph and performing the line mutation.

