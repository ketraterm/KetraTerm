# KetraTerm Session (`:ketraterm-session`)

`ketraterm-session` is the runtime synchronization boundary between transport, parser, core, input encoding, and render publication.

It uses coroutines for lifecycle orchestration, synchronized-output timeout handling, and conflated render publication. Transport byte consumption, parser/core mutation, input encoding, response writes, and borrowed frame reads remain synchronous.

## Runtime model

- `TerminalSession.state` retains `Created`, `Running`, or `Closed(TerminalSessionCloseEvent)`.
- `TerminalSession.renderGeneration` publishes only successfully promoted frames.
- `TerminalSession.renderPublisher` owns the leased primitive cache consumed by renderers.
- A session has one active render viewport. Use separate sessions for independently scrolling views.
- `mutationLock` protects parser/core mutation and borrowed frame reads.
- Reentrant `outboundWriteLock` preserves exact input and core-response ordering.
- The transport contract already guarantees serial, ordered inbound byte delivery, so no additional inbound lock is used.

See [session-concurrency-locks.md](docs/session-concurrency-locks.md) and [asynchronous-render-coalescing.md](docs/asynchronous-render-coalescing.md).

## Usage

```kotlin
val terminal = TerminalBuffers.create(width = 80, height = 24)
val session = TerminalSession.create(
    terminal = terminal,
    connector = connector,
    hostEvents = hostEventSink,
    hostPolicy = HostPolicy(),
    inputPolicy = TerminalInputPolicy(),
)

val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
scope.launch {
    session.renderGeneration.collect {
        session.renderPublisher.readCurrent { published ->
            rendererCache.updateFrom(published)
        }
    }
}
scope.launch {
    session.state.collect { state ->
        if (state is TerminalSessionState.Closed) {
            // React to local close, remote exit, or transport failure.
        }
    }
}

session.start(columns = 80, rows = 24)
session.requestRender(scrollbackOffset = 0)
```

Collectors own their scopes. Closing the session emits `Closed` before its child jobs are cancelled, so current and late collectors can observe the terminal lifecycle result.
