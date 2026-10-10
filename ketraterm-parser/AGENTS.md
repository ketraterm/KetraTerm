# Terminal Parser Agent Guide

Read the root `AGENTS.md` before working here. `ketraterm-parser` turns ordered
host-output bytes into semantic sink calls; it does not own a terminal grid.

## Parser Boundary

- Own UTF-8 recovery, ANSI routing, sequence collection, charset mapping, and
  grapheme assembly.
- Send semantic intent through `TerminalCommandSink`. Cell width, cursor bounds,
  wrapping, storage, mode persistence, and host permissions belong downstream.
- Observe effective screen selection through `isAlternateScreenActive`; do not
  maintain a second authoritative screen flag.
- Keep public parser calls serialized and non-reentrant. Callbacks are synchronous;
  borrowed arrays must not escape their documented call lifetime.

## FSM and Dispatch Rules

- Preserve the split between byte classification, state transitions, collection,
  and semantic dispatch described in [Module.md](Module.md).
- Match CSI structural signatures, not final bytes alone. Preserve omitted fields,
  colon subparameters, and overflow state until dispatch decides their meaning.
- Bound collection before decoding. Unsupported strings must drain without
  printing their bodies or being reclassified as another protocol.
- Respect string-local control handling. Cancellation must discard partial
  commands; ordinary C0 behavior differs between OSC, DCS, and ignored strings.
- Host response and clipboard permissions are outside parser policy. Follow the
  root security rule when introducing query semantics.

## Unicode and Text Rules

- `TerminalParser` owns printable UTF-8 decoding. `PrintableProcessor` accepts
  decoded codepoints or the ASCII fast path, then applies charset mapping.
- Keep segmentation and retained text in parser/unicode. Core owns width and
  placement; never infer grid effects here.
- Publish full retained prefixes through `updatePreviousCluster`. Read-boundary
  publication preserves segmentation context; termination and reset clear it.
- Overflow must advance segmentation context without creating extra writes or
  splitting a cluster merely because storage filled.
- Use packed properties through `UnicodeClass` and generated classification tables.
  Regenerate tables with `tools/generate-unicode-tables.ps1`; update pinned Unicode
  test resources deliberately, independently of the generator.

## Testing

Use explicit semantic expectations with recording sinks. Test byte classes,
matrix transitions, action/state invariants, dispatchers, UTF-8, charsets, and
segmentation independently, then exercise the complete byte-stream parser.

Cover omitted/colon/overflowing parameters, malformed UTF-8 followed by controls,
CAN/SUB, string termination, unsupported input, exact collection bounds, grapheme
overflow, and splits around every relevant byte. A parser protocol change also
requires real byte-stream tests in `ketraterm-host`.

From the repository root, run `./gradlew spotlessApply` and
`./gradlew :ketraterm-parser:test`, plus the affected host tests. Follow the root
guide for feature-map updates. Do not copy capability or deferred-work inventories
into this file.
