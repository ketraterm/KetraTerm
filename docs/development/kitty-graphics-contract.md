# Kitty graphics implementation contract

This document defines the planned graphics implementation contract, not current
runtime support. The [gap map](../terminal-feature-gap-map.md#optional-protocol-work)
owns delivery scope and support status. Sender provenance, exact input bytes and
regeneration instructions live with the [fixture corpus](../../tools/kitty-graphics/README.md).

The reference is Kitty revision
[`1c5a4829ae4dd472017f592b1424381d5ece7575`](https://github.com/kovidgoyal/kitty/tree/1c5a4829ae4dd472017f592b1424381d5ece7575).
The pinned [specification](https://github.com/kovidgoyal/kitty/blob/1c5a4829ae4dd472017f592b1424381d5ece7575/docs/graphics-protocol.rst)
defines the wire vocabulary; its [implementation](https://github.com/kovidgoyal/kitty/blob/1c5a4829ae4dd472017f592b1424381d5ece7575/kitty/graphics.c)
resolves underspecified behavior. KetraTerm choices are identified below rather
than presented as protocol requirements. A sender capture proves emitted bytes,
not receiver behavior or complete application compatibility.

## Command and transfer semantics

Recognize `ESC _ G ... ESC \` separately from ignored APC/SOS/PM strings. Keep
header parsing primitive, preserve omitted fields, and validate signed values
separately from unsigned 32-bit image IDs, image numbers and placement IDs.
Header parsing, Base64 and chunk assembly must not build strings or maps.

| Operation | Required behavior |
| --- | --- |
| `a=t` | Validate and retain the image without displaying it. |
| `a=T` | Validate, retain and place using the cursor at receipt of the final chunk. |
| `a=p` | Place an existing image; missing references fail without cursor movement. |
| `a=q` | Validate the actual requested format and medium without storing, replacing or displaying an image. Discard queries without nonzero `i`, without replying; `I` cannot substitute. Finish eligible replies before later input, including DA1. |
| `a=d` | Abort an incomplete upload and apply the selected deletion. Do not manufacture a delete acknowledgment. |

Use the specification's defaults, including omitted action `t`, format `32`,
medium `d`, and quiet mode `0`. Preserve full `i`, `I` and `p` ranges through
`4294967295`; reject simultaneous nonzero `i` and `I`. An image number allocates
a fresh terminal-assigned ID; later number lookups select the newest match.
Anonymous images and zero-ID placements can coexist. A nonzero image/placement
ID pair updates one placement.

Decode Base64 incrementally into bounded transfer storage. Accept padded and
unpadded final groups; reject impossible length and invalid alphabet. Nonfinal
chunks must end on a complete Base64 group. The first chunk establishes metadata;
continuations may carry `m`, `q`, and the same action repeated by the pinned Kitty
writer. Conflicting continuation metadata rejects the transfer. The wire limit
below deliberately accommodates that writer's 128 KiB payload chunks, despite
the specification's older 4096-byte guidance. It does not raise OSC/DCS limits.

Retain one incomplete upload per session, recording its originating screen.
No image or placement becomes visible before complete validation. A valid delete,
cancellation, reset, EOF, screen switch, collection overflow or malformed graphics
continuation discards staging. Unknown non-graphics APC strings drain without
affecting a graphics upload. For malformed graphics commands, aborting staging is
a KetraTerm recovery choice; the reference parser can leave a prior upload pending.
Drain rejected strings to their terminator so payload never becomes text.

Replacement is transactional: keep the old image and placements until a complete
replacement validates, then remove all old placements and install the new resource
atomically. `a=t` leaves it undisplayed; `a=T` creates only the requested new
placement. Failed replacement preserves the old image. This differs deliberately
from Kitty's eager removal at the start of retransmission. Queries never evict
retained images to obtain working space.

Raw RGB/RGBA payloads must have exactly the expected byte count. `o=z` requires
one complete zlib stream with bounded output and no trailing compressed data.
For `f=100,o=z`, the specification requires positive `S` giving the exact expanded
PNG-stream size. Reject missing/zero `S` and values exceeding the staging bound
before inflation. PNG IHDR dimensions determine decoded size.

Unknown header keys and syntactically malformed commands are discarded without
reflecting input into replies. Recognized but unavailable formats, media or
actions return a fixed `ENOTSUP` failure when eligible; this is a KetraTerm error
choice, not a mandated Kitty code. Known usage hints `N` may be ignored. Never
silently reinterpret virtual or relative placement as ordinary placement.

Use fixed error text: `EINVAL` for invalid values/data, `ENOENT` for missing
references, `EFBIG` for an individual transfer/dimension limit, and `ENOSPC` for
admission after permitted eviction cannot satisfy storage/count limits. Ordinary
anonymous commands (`i=I=0`) receive no success/error reply. Replies for eligible
identified commands retain their image/placement identifiers; `q` only suppresses
otherwise eligible replies. `q=1` suppresses success, `q=2` suppresses all replies,
and intermediate chunks have no success reply. Existing
terminal-response denial suppresses every reply, including failure. Inline
graphics has no window-manipulation permission dependency. Update the explicit
reply allowlist when implementation lands; a probe must exercise the configured
decoder rather than return unconditional success.

## Placement and screen ownership

Core owns separate primary and alternate image/placement namespaces, sharing the
session resource budget. Internal immutable resource identity is independent of
reusable client IDs. Keep placement fields in reusable primitive arrays and
pixel resources outside text-cell storage.

Placements retain source crop, cell position, pixel offsets, requested cell span,
z-index and IDs. Clip crops against the source image. Apply `c`/`r` aspect fitting
and `X`/`Y` offsets as specified; do not assume all placements display the entire
image. Current Yazi uses many cropped one-cell placements. `C=1` preserves the
cursor and pending-wrap state. Missing cell-pixel geometry must produce an eligible
failure for operations that need it, rather than inventing cell dimensions.

For default cursor movement, follow the pinned Kitty implementation rather than
the specification's conflicting prose about moving down by the row count.
[`handle_put_command`](https://github.com/kovidgoyal/kitty/blob/1c5a4829ae4dd472017f592b1424381d5ece7575/kitty/graphics.c#L1437)
advances by the **effective** placement columns and `max(effective rows - 1, 0)`.
Use the span computed from crop, scaling and cell offsets, not simply the supplied
`c`/`r`. Apply movement only after successful placement, including replacement of
an existing placement. Upload-only commands, queries and failed placements do not move.

Match the subsequent
[`screen_handle_graphics_command`](https://github.com/kovidgoyal/kitty/blob/1c5a4829ae4dd472017f592b1424381d5ece7575/kitty/screen.c#L1934)
boundary handling using checked `Long` intermediates and zero-based coordinates:

1. Compute the candidate column and row using that advance. Record whether the
   candidate row lies within the vertical margins **before** horizontal correction.
2. If the candidate column reaches/exceeds the physical grid width, set it to zero
   and add exactly one row. Discard excess columns; this is an immediate graphics
   wrap, independent of DECAWM, not deferred text wrapping or repeated line feeds.
3. If the candidate row exceeds the bottom margin, scroll that region up by the
   excess. Scrolling moves text and placements, without subtracting from the
   candidate row. Then clamp the row to the vertical margins only when DECOM is
   enabled and the recorded candidate was inside them; otherwise clamp to the
   physical screen. This matches Kitty's `screen_ensure_bounds` call.

Skip boundary correction when no movement occurred. For pending wrap, use one
past core's stored column as the logical starting column, matching Kitty's
representation; completed movement clears pending wrap and does not mark a text
line as soft-wrapped. The graphics wrap boundary is the physical grid width, even
with DECLRMM. Actual scrolling uses
KetraTerm's [existing core policy](../../ketraterm-core/docs/terminal-core-contract.md):
cap the excess at the scrolling-region height, preserve cells outside horizontal
margins, and apply the existing primary-history eligibility rules. Those bounded
scrolling rules are KetraTerm policy, not a claim of complete Kitty boundary parity.

Required cursor cases for the implementation tests, on a 10-column, 6-row grid:

| Start `(column,row)` | Effective span `columns × rows` | Controls / vertical margins | Final cursor | Rows scrolled |
| --- | --- | --- | --- | --- |
| `(2,1)` | `1 × 1` | Defaults, `[0,5]` | `(3,1)` | 0 |
| `(2,1)` | `3 × 2` | Defaults, `[0,5]` | `(5,2)` | 0 |
| `(8,1)` | `2 × 1` or `25 × 1` | Defaults, `[0,5]` | `(0,2)` | 0 |
| `(8,5)` | `2 × 1` | Defaults, `[0,5]` | `(0,5)` | 1 |
| `(2,4)` | `3 × 3` | Defaults, `[0,5]` | `(5,5)` | 1 |
| `(8,5)` | `2 × 1` | `C=1`, `[0,5]` | `(8,5)` | 0 |
| `(8,3)` | `2 × 1` | DECOM off, `[1,3]` | `(0,4)` | 1 |
| `(8,3)` | `2 × 1` | DECOM on, `[1,3]` | `(0,3)` | 1 |
| `(2,2)` | `1 × 4` | DECOM on, `[1,3]` | `(3,5)` | 2 |

Composition has three bands: `z >= 0` above text; `-1073741824 <= z < 0` below
text but above cell backgrounds; lower values below nondefault cell backgrounds.
Equal z-order follows the reference image-ID ordering. Selection, cursor and
host overlays remain renderer-owned. Reuse existing viewport geometry for
padding, fractional scrolling and clipping.

Lowercase deletion removes selected placements while retaining reusable image
data. Uppercase also releases selected data once no placements reference it;
ID/number/range deletion must handle images with no placements. Deleting visible
placements must not accidentally remove retained history placements.

Scroll graphics with text. Margin scrolling moves only fully contained
placements and clips them when they cross the scrolling region. ED2 clears
visible images; ordinary partial text erasure leaves ordinary graphics intact.
KetraTerm's RIS policy clears both graphics namespaces and transfer state,
including primary history images; the reference can preserve history images.
DECSTR preserves retained images while discarding an incomplete transfer.
Clearing alternate entry clears that screen; non-clearing switches preserve its
namespace. Local `clearScreen`
removes visible placements while preserving history; `clearAll`/`eraseBuffer`
remove both active viewport and history graphics; ED3 (`eraseScreenAndHistory`)
removes history graphics while preserving visible placements. History eviction
drops placements that can no longer be displayed.

Ordinary graphics keep physical cell coordinates on width reflow; they do not
follow logical text-line IDs. Same-width height shrink adjusts rows by the actual
content lines removed from the live screen. Recompute geometry on cell-size
changes. Alternate resize follows KetraTerm's existing alternate-grid recreation
and clears alternate placements; this is a KetraTerm policy, not reference parity.
Primary graphics remain attached to their own screen. These resize decisions
require focused reference and core tests before publication. They do not change
text reflow policy.

## Image lifetime and render publication

The render boundary adds one narrow immutable image-resource contract: dimensions,
a fixed pixel encoding, and bulk pixel copying. The encoding is unassociated
8-bit sRGB ARGB in 32-bit integers; raw arrays are never exposed for mutation.
This resource alone may outlive a frame callback. All existing frame and placement
arrays remain borrowed. No public acquire/release API or general resource registry
is needed for JVM-owned immutable pixels.

Publication copies placement metadata and resource references, not bitmap pixels.
Replacing or deleting an image cannot mutate a resource held by an older frame.
Clear unused reference-array tails, failed-copy staging, recycled caches and old
source bindings. Count unique pixel resources once within each cache, regardless
of placement count. Existing publisher leases protect placement snapshots.

Graphics changes advance frame and graphics generations without dirtying every
text row. Cache keys include source identity, resource identity, viewport,
structure and cell geometry. Begin with conservative repaint on graphics changes;
add finer damage only if measurements justify it. Empty graphics state takes a
constant-time branch in existing structural operations.

Swing prepares a bounded `BufferedImage` cache outside painting. Copy each new
resource once into its raster, premultiply there if needed, and reuse it for
every placement. Painting performs no decoding, image construction or per-placement
wrapper allocation. Splitting background/text/image composition must preserve
existing text, selection and cursor ordering.

Closing a session discards incomplete transfers but retains the final image state
for frozen-terminal presentation. Rebinding/disposal drops the view's raster
cache and references. External consumers retaining immutable resources own that
additional memory; session limits cannot cap arbitrary consumer retention or
JVM heap commitment.

## Resource limits

These are initial KetraTerm defaults, not wire-protocol constants. Keep one
authoritative runtime limits value when implementation begins; this table is
its design baseline, not a second set of production constants. Use checked `Long`
arithmetic before converting to JVM array sizes. Allocate on demand; do not
reserve maximum capacities for terminals that never receive images.

| Resource | Limit and accounting |
| --- | --- |
| APC header | 1024 bytes, excluding introducer/separator/terminator |
| One encoded payload chunk | 128 KiB of Base64 bytes |
| Encoded upload | 48 MiB across all chunks; counted, never retained as a complete encoded string |
| Decoded transfer / expanded PNG stream | 33 MiB each, including container overhead |
| Image dimensions | Each edge at most 8192; at most 8,388,608 pixels / 32 MiB canonical ARGB |
| Retained images | 64 MiB canonical pixels and 256 image records across both screens |
| Placements | 65,536 records across both screens, including history; enough for Yazi's per-cell path |
| Active transfer/decode | One operation; 192 MiB admission allowance for bounded staging/native/canonical data and estimated codec headroom; see qualification below |
| Each render snapshot | At most 64 MiB unique referenced pixels and the placement count above |
| Swing raster cache | 64 MiB pixel data per bound view; evict unused entries before admitting another raster |

The image-size limit admits a 3840x2160 RGBA image. Enforce dimensions before
decoder raster allocation, and byte/count limits before growth or publication.
Account PNG's possible 16-bit native samples: at this pixel bound the native
RGBA raster can occupy 64 MiB before its 32 MiB normalized result exists.

Evict unplaced images first, then least-recently-used images and their dependent
placements, using a monotonic operation counter rather than wall time. Validate
a new image completely before evicting for its commit. Fail if one image can
never fit, or placement admission cannot satisfy its bound. Replacement must
reserve transient capacity without destroying the previous image on failure.

With three publisher caches and one EDT cache, independent old versions can
retain more memory than the core quota. A conservative built-in payload envelope
is `64 + 3*64 + 64 + 64 + 192 = 576 MiB`: core, published snapshots, EDT snapshot,
Swing rasters, and active decode. This is a conservative live payload estimate,
not a per-session reservation or total-process-memory promise. Primitive metadata,
JVM object overhead, GC lag and platform graphics surfaces are additional; measure
them separately. Reusing unchanged resources normally avoids this multiplier.
ImageIO provides no allocator-budget API: the 192 MiB allowance is not an enforced
cap on opaque codec allocations. Owned byte/pixel/count limits are hard bounds;
adversarial heap profiling must validate codec headroom before PNG support ships.
Do not advertise a total memory ceiling based on this estimate.

## Decoder boundary and ordering

Use one internal PNG decoder in `ketraterm-session`, separate from coordination.
Use the JDK's PNG `ImageReaderSpi` from module `java.desktop`, selected through
public APIs, with a per-operation reader. No new codec dependency or module is
required. ImageIO provider initialization may run trusted classpath providers;
do not describe this as a plugin-free discovery mechanism. Select the JDK PNG
provider explicitly rather than decoding arbitrary detected formats.

Use an owned, bounded byte-array-backed `ImageInputStream`; no file cache, global
ImageIO settings or internal JDK exports. First validate signature, IHDR, chunk
lengths/order, CRCs, known critical chunks and final IEND; reject truncated/trailing
streams. Then compact the exclusively owned staging array in place to
`IHDR`/`PLTE`/`tRNS`/`IDAT`/`IEND`, merging consecutive IDAT payloads and recomputing
their combined length/CRC. Enforce format-defined palette/transparency lengths.
The reader receives this bounded logical length, without another image-sized copy.
This prevents compressed metadata and tiny-chunk object floods; `ignoreMetadata=true`
alone does not. The stream byte limit bounds the allocation-free validation pass.
Validate remaining codec headroom with hostile-input tests and profiling. Close
the input and dispose the reader on every exit. ImageIO does not validate all
PNG CRCs itself.

Read the native raster: forcing an 8-bit destination loses some 16-bit `tRNS`
transparency in the JDK PNG reader. Normalize samples explicitly: replicate grayscale
channels, resolve palette entries and alpha, scale sub-byte samples, and take the
high byte of 16-bit samples. Initially treat samples as sRGB and ignore embedded
profiles/gamma metadata: this is a KetraTerm fidelity limitation, not Kitty parity,
and is tracked in the gap map. Do not use generic `BufferedImage.getRGB` for grayscale;
its color-space conversion changes sample values. Only immutable normalized
pixels cross into core/render contracts; AWT objects stay inside the decoder/UI.

The parser must stop immediately after a graphics command needing decode and
report the consumed byte count. The session keeps the remaining borrowed input
on the same ingress call. A narrow mutation-admission gate serializes that input
batch with other parser/core and host-policy mutations: geometry, clear, theme,
width policy, cursor defaults and window state. Its lock order is admission gate,
then mutation lock, then outbound lock. Release the mutation lock during
synchronous decoding, then reacquire it to commit/reply before parsing later bytes.
Frame/mode reads and ordinary input admission remain available during decode.
Input/paste-policy setters retain their existing outbound serialization; they do
not acquire this gate. Do not add a graphics decode queue, deferred placement
model or per-command future.

### EDT admission and lifecycle

Synchronous gated methods can wait for decoding. **No EDT path may wait for this
gate**, directly or through a wrapper, failure handler or disposal callback.
Before enabling decode, adapt all of these entry paths:

| Entry path / owner | Required handling |
| --- | --- |
| `SwingTerminal.bindOnEdt`, `reloadSettingsOnEdt`, `applySettingsToSession` | Apply captured theme, width-policy and cursor-default updates off the EDT; initial binding is included. Keep component settings/metrics on the EDT. |
| `SwingTerminal.resizeSessionToVisibleGridOnEdt` | Move both `tryUpdateViewportPixels` and `tryResizeViewport` admission off the EDT. Capture geometry/viewport anchors first; install resize results and selection changes on the EDT. |
| `TerminalWindowResizeController.request` fallback | The dispatched fallback calls `session.resize`; route it through the same ordered geometry work as the bound view, after capturing the visible size on the EDT. Window resizing itself stays on the EDT. |
| Standalone `TerminalPane.reloadSettings`, IDE `KetraTermTerminalPane.reloadSettings`, and `TerminalWorkspace.applySettings` | Move session policy/settings effects to their owning host's work path. Preserve order with view settings; moving only `terminal.reloadSettings` does not cover the subsequent `setHostPolicy`. |
| `SwingTerminal` window-state listeners and `updateMinimizedStateFromAncestor` | Capture minimization on the EDT and admit `setWindowMinimized` off it, including binding-time synchronization. |
| Embedded `TerminalSession.clearBuffer` calls | Perform local clear off the EDT and report its result on completion. `SwingTerminal.clearScreen` sends Ctrl+L through ordinary input admission and remains outside the gate. |
| Tab/project/window closure, pending-tab/startup cleanup, and `IntellijClipboardSession` disposal | Detach UI on the EDT; run session/workspace cleanup under a product lifetime owner off it. Cover `TabManager`, `KetraTermProjectTerminalService` and indirect `TerminalWorkspace.closeTab`/`close` calls. Cleanup must survive cancellation of the disposed view. Startup/connector delivery also stays off the EDT. |

Use the existing binding/product work owners with an explicit non-EDT dispatcher.
Keep pending work bounded and preserve accepted mutation order per session; launching
independent coroutines is not an ordering guarantee. Coalesce superseded geometry,
but do not drop accepted clear operations or policy transitions. Recheck captured
binding/request validity at actual gate admission, not only before waiting, and
again before installing results on the EDT. Report completion/failure without
blocking the EDT or treating a queued request as an applied change. Workers must
not read Swing state, and gate holders must never wait for EDT callbacks.

Preserve reentry rejection across the yield. Closure first claims termination
and closes the connector, then takes cleanup locks; decode commit rechecks closure
and discards a stale result. Failure-triggered closure from EDT input admission
(for example outbound-capacity exhaustion) must claim termination promptly and
hand off connector closure and lock-taking cleanup; offloading only explicit
tab-close actions is insufficient. No late image can appear after termination.
Graphics delivery can block its ingress caller; custom connectors must deliver such output
off UI threads. The built-in PTY already has a reader thread. This gate/yield is
a planned API/concurrency change and must be tested against the existing
[session contract](../../ketraterm-session/docs/session-concurrency-locks.md)
when implemented, not silently inferred from today's `accept` API.

## Verification gates

The sender corpus tests verify immutable source revisions, fixture hashes,
exact APC metadata/chunks and decoded input pixels independently of production
parsing. Decoder-boundary tests pin native PNG sample behavior. Neither is a
graphics conformance test for KetraTerm.

Subsequent implementation tests must assert exact queries/quiet replies, command
ordering, transfer recovery, invalid/oversized data, unsigned IDs, identity reuse,
placement geometry, all deletion selectors, scroll/margin/resize behavior, screen
lifecycle, pinned-frame stability, failed-copy cleanup and frozen presentation.
Exercise the cursor cases above plus pending wrap, DECAWM off, horizontal margins,
large spans and failure atomicity. For each EDT entry family above, hold decode
behind an entered/release handshake, submit the UI request, and observe a later
EDT marker before releasing decode. Then verify exact mutation/completion order,
policy revocation, stale-binding rejection and cleanup after disposal or capacity
failure. Use controlled schedulers or explicit handshakes, never elapsed-time assertions.

Replay the pinned senders through the complete pipeline and run real application
smoke tests before declaring support. Benchmark text without graphics, unchanged
image frames, dense Yazi placements, scrolling and replacement separately.
Warmed frame processing must avoid new allocations; upload/decode allocation and
retained heap have separate bounds. Review any new public API and ABI snapshots,
then run the affected module, published-consumer and formatting checks.
