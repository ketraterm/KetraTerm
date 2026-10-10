# KetraTerm Completion (`:ketraterm-completion`)

Command-line completion for Kotlin/JVM hosts. The engine combines command
specifications, host-provided sources, and optional in-memory learning into
progressive, ranked suggestions with explicit text replacement ranges.

The module has no terminal, UI, process, or filesystem dependency. Its coroutine
API can serve a headless caller or a host-owned suggestion UI.

## Add the dependency

Use the `io.github.ketraterm:ketraterm-completion` artifact with the version
alignment and repository setup in the [root README](../README.md#using-the-libraries).
The module exposes `kotlinx-coroutines-core` transitively.

## Request completions

Request a suggestion from the built-in command catalog:

```kotlin
import io.github.ketraterm.completion.api.TerminalCompletionEngines
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import kotlinx.coroutines.flow.last

suspend fun main() {
    val engine = TerminalCompletionEngines.fromSources(sources = emptyList())
    val line = "git che"
    val request = TerminalCompletionRequest(
        commandLine = line,
        cursorOffset = line.length,
        shellCapabilities = TerminalShellCapabilities.POSIX,
    )
    val candidate = engine.completions(request).last().firstOrNull() ?: return
    val completedLine = line.replaceRange(
        candidate.replacementStartOffset,
        candidate.replacementEndOffset,
        candidate.replacementText,
    )
    println(completedLine) // git checkout
}
```

`completions` returns a cold `Flow`: collection starts source evaluation.
`last()` waits for the final ranking; an interactive UI should collect each
emission and cancel the preceding collection when its request becomes obsolete.
Each emission is a complete replacement snapshot, rather than a list of additions.

Offsets are UTF-16 indices in the original request. The cursor must be within the
line and must not split a surrogate pair; invalid offsets throw
`IllegalArgumentException`. Apply a candidate only while that captured command
line and editing context are still current. Display text and match ranges are
presentation data; insertion uses `replacementText` and its replacement range.

Select shell capabilities from authoritative host metadata. Use `POSIX` or
`POWERSHELL` for their supported lexical and quoting contracts, and the default
`PLAIN` for other shells. The engine does not infer a dialect from a profile id.

## Customize the engine

Pass `commandSpecs` to `TerminalCompletionEngines.fromSources` to replace the
built-in catalog. `TerminalCommandSpecs.defaults()` returns a shared immutable
catalog; custom `TerminalCommandSpec`, `TerminalOptionSpec`, and
`TerminalArgumentSpec` models describe commands, option values, and positional
arguments. Keep custom catalogs and their nested collections unchanged while
the engine is in use. The engine evaluates its catalog automatically.

Register host sources with `TerminalCompletionSourceEntry`. Source factories in
`TerminalCompletionSources` cover direct paths, fuzzy paths, Gradle tasks, and
dynamic value domains. A custom `TerminalCompletionSource` receives the request,
the engine's already-resolved context, and an output limit. It may perform
bounded suspending host work; it must cooperate with cancellation and move
blocking operations to an appropriate dispatcher. Reusable filesystem access
lives in [completion-host](../ketraterm-completion-host/README.md).

Custom engines can resolve and share context with
`TerminalCompletionContext.resolve(request, commandSpecs)`. See the
[custom-engine contract](docs/completion-architecture.md#custom-engines-and-direct-source-evaluation).

An ordinary source exception is reported through
`TerminalCompletionSourceFailureHandler` and contributes an empty result.
Unexpected errors fail collection and cancel sibling sources. Request
cancellation cancels all source work. See the
[evaluation contract](docs/completion-architecture.md#evaluation-and-failure-behavior)
for concurrency and result bounds.

## Optional learning and localization

Share a `TerminalCompletionLearningStore` through the engine's `learningStore`
parameter. Hosts record authoritative command results and explicit suggestion
feedback. The store performs no I/O; persistence consumes its immutable
`snapshot()` and hydrates through `mergeSnapshot()` once per distinct aggregate
event set.

The optional `replayFilter` constructor argument adds a host restriction to the
built-in plaintext policy. `TerminalCompletionLearningStore(replayFilter = { false })`
retains opaque ranking evidence while disabling plaintext replay. The filter
also applies to imported replay rows; `clear()` removes learning without
replacing the filter or capacity. See the [learning and privacy contract](docs/completion-architecture.md#learning-and-privacy)
for concurrency, failure, and retention rules.

Use `TerminalCommandSpecs.defaults(locale)` for localized catalog descriptions
and `TerminalCompletionMessages` with the engine and applicable source factories
for generated descriptions. Canonical command tokens and insertion behavior
remain unchanged; provider-supplied descriptions remain provider-owned.

## Further reading

- [Module and package ownership](Module.md)
- [Completion architecture and contracts](docs/completion-architecture.md)
- [Swing completion bridge](../ketraterm-ui-swing-host/README.md)
- [Supported behavior](../docs/terminal-feature-map.md) and
  [deferred work](../docs/terminal-feature-gap-map.md)
