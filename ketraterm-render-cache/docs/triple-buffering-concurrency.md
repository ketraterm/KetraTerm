# Triple-Buffered Render Cache Concurrency

`TerminalRenderPublisher` separates a serialized frame-copy writer from readers
of the latest copied frame. It owns three `TerminalRenderCache` instances and
uses short locked sections for buffer selection, publication, and lease counts.
Source copying and reader callbacks run outside that publication lock.

## 1. The Buffering Model

A buffer can be the current front, writer-owned during a copy, available for
reuse, or a retired front still pinned by readers. These are states of the three
buffers, not three permanently assigned slots.

Publication proceeds in three steps:

1. Select a buffer that is not the front, has no readers, and is not writer-owned.
   Wait on the publisher's condition if no such buffer is available.
2. Copy from the source reader into that buffer. The reader owns any terminal
   mutation lock used during its callback.
3. Promote the completed buffer to front, release writer ownership, and wake
   waiting writers. The previous front becomes reusable only after its readers
   finish.

A reader pinned to an older frame does not see later publications. Other buffers
can continue rotating while that old frame remains pinned, as long as a writable
buffer is available. Triple buffering therefore limits storage and avoids
writing into leased frames; it does not guarantee that publication never waits.

Only completed copies become front. If source copying throws, the writer lease
is released and the previous front remains available. Publisher writer calls
must be serialized by their owner; they need not always run on the same physical
thread. The publisher creates no threads or scheduling policy.

## 2. Synchronized leases and reader ABI

Consumers normally use `readCurrent { cache -> ... }`. It acquires the current
front under the publication lock, invokes the callback without that lock, then
releases the lease in `finally`. Callback failures and Kotlin non-local returns
still release the lease. Before the first publication, no callback runs and the
result is `null`; a callback that returns `null` produces the same result.

Within a lease, the cache and every exposed array are borrowed read-only. Finish
all reads before returning. Do not mutate the cache, retain storage for later
access, or reenter publication. Prefer short callbacks: copy to a caller-owned
cache if analysis or drawing needs a longer lifetime.

`acquireFrontLease` and `releaseFrontLease` are the public bridge used by inline
readers in other modules. Each non-null acquisition must be released exactly
once on the same publisher, using the identical cache reference, in a `finally`
block. Acquisition does not create a lease object. A foreign cache or an
unmatched release is rejected before changing counts. A duplicate release while
another reader holds the same cache cannot be detected; correct pairing remains
the caller's responsibility.

Unleased `current()` access is internal to quiescent module tests. Session keeps
its publisher private and exposes `readPublishedFrame` with the same borrowing
rules. For session-specific restrictions, see
[render reader ownership](../../docs/render-reader-ownership.md).

## 3. Source Lifetime

Row identities and generations identify content within one reader instance.
`TerminalRenderCache.updateFrom(reader)` and `updateFromAbsoluteRange` track that
identity and reset before reading a replacement reader. Copying a published
cache with `updateFrom(sourceCache)` preserves its reader identity; publisher
buffer rotation does not begin a new source lifetime.

Owners call `reset` when unbinding a source or replacing the content behind the
same reader. Direct `TerminalRenderFrameConsumer.accept` callers must reset
before changing sources because a frame alone does not identify its owner.
Reset clears source cells and metadata while retaining dimensions and primitive
storage.

`hasFrame` is false initially, after reset, and while an incomplete copy remains.
Only a complete copy makes it true. A failed direct copy may have overwritten
part of the cache, so consumers must not inspect it as a valid frame. The next
successful read recopies the required rows.

Within a source lifetime, shape, structure, or resolved scrollback changes force
row copying. Otherwise, line identity, generation, and wrap changes determine
which source rows need copying. Cursor and frame metadata are refreshed
independently. `historyContentGeneration` and `outputEndAbsoluteRow` retain their
source meanings through cache and range copies.

<a id="4-allocation-free-multi-grapheme-clustered-text-copy"></a>

## 4. Packed Cluster Storage

Cluster copying uses packed primitive storage and avoids constructing a string
for every clustered cell. Capacity growth can still allocate.

- `clusterRefs` maps cells to packed references. Zero means no cluster; the upper
  32 bits encode the codepoint offset and the lower 32 bits encode its length.
- `clusterCodepoints` stores the copied Unicode codepoints referenced by those
  cells. Use `clusterOffset` and `clusterLength` to decode a reference.

Each update builds the next packed cluster payload in a reusable second array
and swaps the payload arrays at completion. Unchanged rows preserve clusters by
copying their payload into that next array and rewriting references. Changed
rows clear their old references before accepting new data. Consequently, skipping
a source row does not mean its cluster payload requires no work.

`clusterText` constructs a string and `cursor` constructs a cursor value when
available. Paint hot paths should use packed cluster storage and primitive
cursor fields. Array capacity may exceed the active frame; only the prefix
addressed by `columns` and `rows` is visible.
