# OSC 8 Hyperlink Registry & Cache Invalidation

The `ketraterm-host` module manages the association of terminal cells with interactive hyperlinks (gated via `OSC 8` sequences) using an efficient, double-indexed LRU cache.

---

## 1. Cell Hyperlink Representation

To maintain flat cell arrays and prevent heap-allocated objects per cell, the rendering layers represent hyperlink links as primitive 32-bit `Int` identifiers:

* **In the cell attribute word**: The link's numeric identifier is packed inside the cell's extended attribute plane (`extraAttrWords`/`hyperlinkIds`).
* **Zero mapping**: A hyperlink ID value of `0` represents a normal text cell containing no hyperlink.

---

## 2. Double-Indexed LRU Registry (`HostCommandAdapter`)

The translation of string-based hyperlink keys (combining a unique ID and a destination URI) to the cell's numeric identifier is managed inside `HostCommandAdapter` using two lookup structures:

```
    [HyperlinkKey(id, uri)] ◄──► [Numeric ID (Int)]
               ▲
               │
    (LinkedHashMap: LRU Cache)
```

1. **`hyperlinkIds` (`LinkedHashMap<HyperlinkKey, Int>`)**:
   * Maps a [HyperlinkKey](../src/main/kotlin/io/github/ketraterm/host/HostCommandAdapter.kt) to its allocated numeric ID.
   * Configured in access-order mode to act as a Least Recently Used (LRU) cache.
2. **`hyperlinkKeysByNumericId` (`HashMap<Int, HyperlinkKey>`)**:
   * Maps the numeric ID back to the hyperlink key. Used by renderers to resolve clicked cell IDs back to actual clickable URIs via `hyperlinkUri(numericId)`.

---

## 3. Eviction & Safety Limits (`HostPolicy`)

To protect terminal memory against unbounded memory growth (e.g. applications writing millions of unique URLs in scrollback logs), the registry is governed by safety constraints in [HostPolicy](../src/main/kotlin/io/github/ketraterm/host/HostPolicy.kt):

* **`maxHyperlinkEntries`**: The maximum number of active hyperlinks retained in the registry (default `4096`).
* **Eviction rule**: An accepted open removes enough least-recently-used entries from both collections to satisfy the captured limit, reserving one slot for a new key. Explicit-key reuse refreshes recency before trimming and preserves that entry. Cells carrying evicted IDs still display text but no longer resolve to URIs.

Policy replacement only publishes a volatile value; it never mutates either registry or emits callbacks. A lowered limit takes effect on the next accepted open under the serialized command owner. Denied, invalid and exhausted new opens do not trigger eviction. Work is bounded by the retained entry count, independently of the configured limit's magnitude.

Each removal callback observes its ID as unresolved, and all removals precede replacement registration. Callback failures propagate and stop the admission; completed removals remain coherent in both indexes, and a later accepted open resumes enforcing the limit. Callbacks must not reenter mutation.

## 4. Identity Lifetime and Reset

An issued numeric ID may resolve to its original URI or become unresolved. It must never resolve to a different URI during the lifetime of its `HostCommandAdapter`. Retained alternate-screen cells, render snapshots, and pending UI actions can outlive the registry entry they reference.

Hard reset clears the registry and active hyperlink metadata without restarting numeric ID allocation. Eviction also leaves the allocation sequence intact. Both operations invalidate old links without allowing later output to retarget them. Soft reset clears only the active hyperlink; retained registry entries remain resolvable.

Numeric IDs increase from `1` through `Int.MAX_VALUE`. Anonymous OSC 8 links use their numeric ID to distinguish separate occurrences; explicit `(id, URI)` pairs reuse their existing entry while it is retained. Neither reset nor eviction recycles an issued numeric ID.

After the last positive ID is allocated, new entries are refused without evicting retained entries. Their text is still printed with hyperlink ID `0`, and active hyperlink metadata is cleared. Existing explicit pairs can still reuse retained entries. Hard reset does not undo exhaustion; a new adapter starts a new identity lifetime. This policy preserves the primitive cell representation and bounded registry without wraparound aliases or per-frame work.
