# Hyperlink detection

This contract applies to host-provided `SwingHyperlinkDetector` implementations.
The [README](../README.md#hyperlink-detector-contract-and-migration) introduces
configuration. Terminal-authored OSC 8 links use `hyperlinkHandler`; detected
links carry their own `SwingHyperlinkAction`.

## Execution and ownership

`detect(request)` is suspending and runs outside painting and Swing event handling.
Return a completed `List<SwingHyperlink>` and do not mutate the list or results
later. An empty list is successful analysis without links. Failures and
cancellation must throw; propagate coroutine cancellation rather than reporting
empty success. Discovery may allocate bounded strings, results, and collections.
Do not access Swing state from the detector.

Calls are serialized within each context. The binding owns cancellation and
rejects obsolete results after source, binding, provider, or analysis changes.
A detector that ignores cancellation occupies its context until it returns.
Activation and hover callbacks run on the EDT, outside painting.

| Context | Use when |
| --- | --- |
| `INDEPENDENT_LINE` | Each logical line can be analyzed without preceding lines; unchanged lines may be omitted |
| `ORDERED_CONTENT` | Provider state consumes source order or results may refer to earlier lines |
| `INDEPENDENT_AND_ORDERED` | One detector supports both independently scheduled lanes and permits concurrent calls between them |

Each request selects one lane. Independent text-derived links do not wait for
ordered provider work.

## Source coordinates and dependencies

Requests defensively copy logical text and physical-row anchors. Soft-wrapped
rows form one logical line; wrap padding and wide trailing cells are omitted,
and one newline is appended. Positions contain the first absolute physical row
of the logical line and a UTF-16 offset in its text. They are not viewport rows,
cell columns, or global console offsets.

Use `request.range` or `request.hyperlink` to create ranges. A result contains
its highlighted source range, validation dependency range, action, optional
complete copyable URI, prepared presentation, and activation policy. Dependencies
must include all characters that affect recognition or navigation, including
token delimiters. The single-line factory defaults to the whole logical line for
independent results and the supplied batch for ordered results; narrow them only
when the action is independent of the omitted context.

Ordered results identify the source position consumed when they were produced
through `consumedThrough`. They may highlight earlier retained lines. Unknown or
out-of-bounds coordinates are ignored. `lineFirstId` supplies a stable source
identity for host historical metadata; platform-specific cumulative console
offsets remain host-owned.

## Configuration and ordered state

`configurationGeneration` is an equality-only invalidation value. Publish the
changed configuration and a distinct generation before emitting
`configurationChanges`. A notification with the same generation does not
invalidate results; without a notification, the new generation is observed at
the next reconciliation. The binding owns collection of the notification flow.

Configuration refresh keeps prepared links until replacement results arrive,
including successful empty replacements. Replacing the detector instance retires
its old actions immediately; use replacement when stale actions must not remain
usable.

Ordered state belongs to the request's binding, source, provider, and analysis
epochs. Reconstruct it when earlier content changes or an interrupted invocation
has tainted it. An `analysisEpoch` change requires replay from retained source;
ordinary eviction advances `firstRetainedRow` without restarting the chain.
Implement `discardOrderedState` when retaining provider state. The owner invokes
it only after the ordered call exits. It must be nonblocking, thread-independent,
and separate from independent-lane state.

## Retention, projection, and interaction

Binding-owned discovery retains occurrences and successful empty analysis across
scrolling. Frame preparation projects prepared links into visible cells rather
than calling providers. Changed dependencies invalidate activation before
rediscovery; reflow and source replacement invalidate the affected source state.
Temporary hiding preserves discovery. Unbinding or disposal cancels it and
releases retained results.

OSC 8 links win overlap resolution, followed by visible links, narrower ranges,
and stable provider order. `MODIFIER` activation uses Ctrl or Cmd on macOS;
`DIRECT` uses an ordinary primary click. Activation occurs on release only when
the pressed occurrence still matches and no drag occurred. Application mouse
reporting takes priority unless Shift forces local interaction. Hover callbacks
are balanced per occurrence even if results share an action.

Resolve colors and framework presentation before publication. Painting reads
prepared normal, hovered, active, and followed styles; it performs no provider
lookup. Terminal-authored underline and concealment retain precedence. Supply
`uri` when the complete destination can be copied. Context menus capture the
action and URI when opened, retaining their original target across later output
or rebinding.

## Migration

Detectors return `List<SwingHyperlink>` from suspending `detect(request)` instead
of publishing through a sink. Replace `LOGICAL_LINE` / `VIEWPORT` contexts with
`INDEPENDENT_LINE` / `ORDERED_CONTENT`. Positions use logical-line row anchors
and UTF-16 offsets instead of cumulative console offsets.
