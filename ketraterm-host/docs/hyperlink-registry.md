# OSC 8 Hyperlink Registry & Cache Invalidation

`HostCommandAdapter` retains bounded OSC 8 metadata while core cells and render
frames carry numeric hyperlink IDs. Registry access must be serialized with
adapter commands and core mutation. A session provides that boundary for its
consumers.

## 1. Cell Hyperlink Representation

A cell's `hyperlinkId` is an `Int`: zero means no hyperlink; positive values
identify registry entries. Closing OSC 8 clears the active writing hyperlink
without removing entries used by already written cells.

`hyperlinkUri(id)` returns the retained URI or `null`. A cell can remain visible
after its entry is evicted, reset, or otherwise unavailable, so consumers must
handle a failed lookup. The adapter checks OSC 8 permission and URI/ID length
limits; it does not enforce a browser scheme allowlist or open a destination.
The embedding host owns validation and explicit activation of a resolved URI.

## 2. Double-Indexed LRU Registry (`HostCommandAdapter`)

An access-ordered key-to-ID index controls retention; an ID-to-key index serves
URI lookup. Both indexes change together under the serialized command owner.

An explicit application `(id, URI)` pair reuses its numeric ID while retained
and refreshes its recency. An anonymous link creates a distinct occurrence even
when its URI matches a previous one. URI lookup itself does not refresh recency.

`hyperlinkRegistered` is emitted only for a new entry. `hyperlinkRemoved` follows
an eviction, after the removed ID is unresolvable. Hard reset emits one
`hyperlinksCleared` event for a nonempty registry instead of per-ID removals.
These callbacks do not activate links.

## 3. Eviction & Safety Limits (`HostPolicy`)

[`HostPolicy`](../src/main/kotlin/io/github/ketraterm/host/HostPolicy.kt) bounds
retention and accepted payload sizes:

| Setting | Default | Meaning |
| --- | --- | --- |
| `maxHyperlinkEntries` | `4096` | Maximum retained entries after an accepted open; must be positive |
| `maxHyperlinkUriLength` | `4096` | Maximum URI length in UTF-16 code units; must be nonnegative |
| `maxHyperlinkIdLength` | `256` | Maximum application ID length in UTF-16 code units; must be nonnegative |

Denied or oversized opens clear the active hyperlink instead of truncating a
destination or attaching the previous link to subsequent text.

Policy replacement only publishes a volatile value; it does not mutate the
registry or emit callbacks. The next accepted open captures the entry limit and
evicts enough least-recently-used entries from both indexes to satisfy it,
reserving a slot for a new entry. Explicit-key reuse refreshes recency before
trimming and preserves that entry. Denied, invalid, and exhausted new opens do
not trigger eviction. Work depends on the retained entry count, independently
of the configured limit's magnitude.

All removal callbacks precede replacement registration. Callback failures
propagate and stop admission; completed removals remain coherent in both
indexes. A later accepted open resumes enforcing the limit. Callbacks must not
reenter mutation.

## 4. Identity Lifetime and Reset

An issued numeric ID resolves to its original URI or becomes unresolved. It
never resolves to a different URI during that adapter's lifetime. Cells,
copied render data, and pending host actions may outlive a registry entry.

Soft reset clears only the active hyperlink; retained entries remain
resolvable. Hard reset clears active metadata and the registry without
restarting allocation. Eviction likewise does not recycle IDs. Reopening an
evicted explicit key therefore receives a new ID.

IDs increase from `1` through `Int.MAX_VALUE`. Once exhausted, new entries are
refused without evicting retained entries; subsequent text receives ID zero.
Retained explicit pairs can still be reused. Hard reset does not undo
exhaustion. A new adapter begins a new identity lifetime, so numeric IDs from
different adapters must never be mixed.
