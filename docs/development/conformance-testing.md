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
invariant campaign focused on KetraTerm's own resize and reflow policy.

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

## Streaming placement

The [streaming placement contract](../reference/protocol.md#streaming-grapheme-placement)
commits published grid effects. `HostGraphemePolicyTest` verifies that policy.
Four retained `HostGraphemeTest` oracles test the stronger, unsupported requirement
that every byte split produces identical placement:

- Narrowing does not restore an overwritten neighbor.
- Widening at a margin can differ from an initially wide write.
- Late narrowing does not undo history eviction on a one-cell grid.
- Late narrowing does not move an already wrapped cluster back.

The `knownR06Failure` helper reports only recorded assertion failures as
skipped/aborted. Additional failures, changed messages, or unexpected passes fail
the test and require review. A skip does not establish that later iterations of
an oracle passed. Ordinary grapheme and byte-stream tests remain active.

`./gradlew :ketraterm-host:test --tests '*HostGrapheme*' --tests '*KnownR06FailureTest'`
runs this focused verification.
