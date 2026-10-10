# KetraTerm Completion Host Support (`:ketraterm-completion-host`)

Local filesystem access for path completion. This module connects the
[completion engine](../ketraterm-completion/README.md) to bounded, suspending
directory scans without depending on a terminal session, UI, or product.

## Add the dependency

Use `io.github.ketraterm:ketraterm-completion-host` with the version alignment
and repository setup in the [root README](../README.md#using-the-libraries).
The completion API and `kotlinx-coroutines-core` are exposed transitively.

## Enable local path completion

Register the local provider through the completion engine's path source. Create
the engine once and reuse it for requests within the same host context:

```kotlin
import io.github.ketraterm.completion.api.TerminalCompletionEngine
import io.github.ketraterm.completion.api.TerminalCompletionEngines
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSourcePrior
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider

fun localPathCompletionEngine(): TerminalCompletionEngine =
    TerminalCompletionEngines.fromSources(
        sources = listOf(
            TerminalCompletionSourceEntry(
                TerminalCompletionSources.path(TerminalLocalFileSystemProvider()),
                TerminalCompletionSourcePrior.DIRECTORY_PATH,
            ),
        ),
    )
```

Supply the latest authoritative working-directory URI and shell capabilities
through each `TerminalCompletionRequest`. For a host-selected local `Path`,
`path.toAbsolutePath().toUri().toString()` supplies the URI; terminal metadata
must retain its original authority for validation. Without a working-directory
URI, the path source returns no candidates.

The host cancels obsolete completion requests; the provider requires no cleanup.
See the [request contract](../ketraterm-completion/README.md#request-completions).

## Path resolution

`TerminalCompletionPathResolver` resolves the engine's lexical
`TerminalDirectoryListingRequest` into a normalized absolute local `Path`.
It accepts relative and absolute paths, `~/` using the configured home directory,
and Windows drive or UNC syntax when Windows support is enabled. Requests use
forward slashes; shell parsing and quoting stay in the completion engine.

`TerminalLocalFileUriResolver` accepts `file:` working-directory URIs with an
empty authority or `localhost`. Unsupported schemes, remote authorities, and
malformed paths produce no local result. Resolution is lexical and performs no
filesystem access; normalization does not confine access to a workspace or
resolve symbolic links. A host with remote filesystems or restricted access
should provide its own `TerminalFileSystemProvider` from the completion API.

## Scan limits and customization

The default `TerminalBoundedDirectoryScanner` reads direct children on
`Dispatchers.IO` through `runInterruptible`. Its defaults are 8,192 visited
entries and a best-effort 50 ms scan budget. The constructor accepts a positive
`maxVisitedEntries`, positive `scanBudgetNanos`, and an `ioDispatcher`.
The time budget is checked between filesystem operations; it is not a hard
deadline for a blocking filesystem call.

Each request scans afresh. Bounds apply before prefix filtering, so large
directories can return incomplete results. Retained entries are sorted by name.

Absent directories and non-directory paths return an empty list. Other
filesystem failures propagate to the completion engine's source-failure
boundary, and coroutine cancellation remains cancellation.

Supply a custom `TerminalDirectoryScanner` to `TerminalLocalFileSystemProvider`
when a host API should scan local paths. Its suspending `scan` implementation
owns dispatcher changes, cancellation, bounds, and deterministic result ordering.
`TerminalDirectoryEntrySnapshot` provides the shared ordering and prefix
projection for an already bounded collection; it does not limit collection size.

## Further reading

- [Module and package ownership](Module.md)
- [Completion evaluation and failure behavior](../ketraterm-completion/docs/completion-architecture.md#evaluation-and-failure-behavior)
- [Swing completion bridge](../ketraterm-ui-swing-host/README.md)
