# Asynchronous Render Coalescing & Event Loops

To prevent UI repainting bottlenecks under high-frequency terminal byte floods (such as running `cat` on a large log file), the `jvterm-session` module utilizes an asynchronous render coalescing loop.

---

## 1. The Render Worker Thread (`renderWorker`)

The session spawns a dedicated, single-threaded ScheduledExecutorService named [renderWorker](file:///c:/Users/gagik/IdeaProjects/terminal-buffer/jvterm-session/src/main/kotlin/io/github/jvterm/session/TerminalSession.kt#L84):

* **Daemon Thread**: Spawns daemon threads (`terminal-render-worker-*`) to avoid preventing JVM shutdown when the session is closed.
* **Isolation**: Decouples the UI painting ticks and PTY reader threads from the rendering cache updates.

---

## 2. Dirty Request Coalescing

When a write operation occurs (e.g. host bytes written to screen), the session marks the display as dirty:

```
               Grid Content Mutated
                        │
                        ▼
            [notifyRenderDirty()]
                        │
       Is renderScheduled already true?
               ├──► YES ──► (Ignore request, coalesced!)
               │
               └──► NO  ──► [renderScheduled.set(true)]
                             │
                             ▼
                   Schedule render task on
                    [renderWorker] thread
                             │
                             ▼
                [publish new frame cache]
                             │
                             ▼
                 Invoke [onDirty()] callback
                             │
                             ▼
                 [renderScheduled.set(false)]
```

### Invalidation Logic:
* **Atomic Bit Flag**: An atomic boolean `renderScheduled` acts as a guard. If a render is already pending on the executor queue, subsequent screen mutations are coalesced without scheduling redundant task payloads.
* **Generation Comparison**: The rendering worker compares the current terminal frame generation against `pendingRenderGeneration`. If the generations match, it skips updating the cache, conserving CPU cycles.
* **Overscan Packing**: Viewport configurations (like scrollback offset and overscan viewport rows) are packed atomically into a single `Long` (`pendingRenderRequest`), guaranteeing atomic updates between UI adjustments and background cache promotion.

---

## 3. Pre-Allocated Response Buffer

For outbound host responses generated dynamically inside the parser loop (such as cursor reports or status queries):
* **`responseScratch`**: The session pre-allocates a static byte buffer of size `1024` bytes.
* **Zero Allocations**: Responses are formatted and copied directly into this buffer and sent to `TerminalHostOutput` in a single system block call, eliminating garbage collector pressure on hot parsing paths.
