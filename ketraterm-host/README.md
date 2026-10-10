# KetraTerm Host (`:ketraterm-host`)

`HostCommandAdapter` maps parser commands to the public core API and delivers
host metadata and requests through `HostEventSink`. It does not own a transport,
perform platform actions, or create worker threads.

The artifact is `io.github.ketraterm:ketraterm-host`. For a complete synchronized
pipeline, start with [`ketraterm-session`](../ketraterm-session/README.md); use the
adapter directly when your application owns parsing and core synchronization.
See the [root README](../README.md) for dependency setup.

## How to Use

Create a single-threaded pipeline with a title callback:

```kotlin
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.parser.api.TerminalParsers

fun main() {
    val terminal = TerminalBuffers.create(width = 80, height = 24)
    var windowTitle = ""
    val events = object : HostEventSink by HostEventSink.NONE {
        override fun windowTitleChanged(title: String) {
            windowTitle = title
        }
    }
    val adapter = HostCommandAdapter(
        terminal = terminal,
        hostEvents = events,
        hostPolicy = HostPolicy(
            notificationPolicy = HostControlPolicy.DENY,
            windowManipulationPolicy = HostControlPolicy.DENY,
        ),
    )
    val parser = TerminalParsers.create(
        sink = adapter,
        clipboardWriteLimitBytes = adapter::clipboardWriteLimitBytes,
    )

    parser.accept("Hello\u001B]2;Example\u0007".toByteArray(Charsets.UTF_8))
    parser.endOfInput()

    check(terminal.getLineAsString(0) == "Hello")
    check(windowTitle == "Example")
}
```

Keep one parser and adapter for each stream. Serialize parser calls, adapter
commands, and core/grid metadata access under the same owner. Input chunks may
be reused after `accept` returns. Call `endOfInput` at EOF; parser `reset` only
discards pending parsing state and is distinct from terminal reset commands.
The adapter has no `close` operation or transport resources to release.

If a direct callback throws, its exception propagates through parsing. Stop
processing that stream rather than assuming parser and terminal state remain
aligned.

## How to Extend: Custom Event Sinks

Implement [`HostEventSink`](src/main/kotlin/io/github/ketraterm/host/HostEventSink.kt)
or delegate unneeded operations to `HostEventSink.NONE`, as above. Callbacks
deliver accepted metadata or requests; the host decides how to display titles,
open links, resize windows, show notifications, or access a clipboard.

Parser callbacks are synchronous and ordered on the mutation caller's thread.
With `TerminalSession`, its mutation lock is held during delivery. Return
promptly, schedule UI work through the host's lifecycle, and do not reenter
session mutation or wait for a UI thread. There is no initial-state replay.
Clipboard-read execution audits can arrive on session workers and require a
thread-safe sink.

## Host policy

[`HostPolicy`](src/main/kotlin/io/github/ketraterm/host/HostPolicy.kt) permits
title, hyperlink, working-directory, notification, window, palette, and terminal
response controls by default; clipboard reads and writes are denied by default.
Select permissions for the whole stream before feeding it. Shell metadata and
process names do not authenticate the source of nested SSH or multiplexer output.

`setHostPolicy` safely publishes an immutable replacement across threads. It
does not revoke retained metadata or execute platform work. A lowered hyperlink
entry limit is applied on the next accepted open, as described in the
[registry contract](docs/hyperlink-registry.md#3-eviction--safety-limits-hostpolicy).

The adapter validates clipboard payloads and separates content-free audits from
write/prompt payloads. It never reads or writes a platform clipboard itself.
Session execution owns clipboard-read deadlines, policy revalidation, and
ordered replies. A direct embedder must provide that execution and response
path when enabling reads.

## Sub-Documentation

- [Command mapping](docs/command-adapter-mapping.md): coordinate conventions,
  screen transitions, reset boundaries, and response policy.
- [Hyperlink registry](docs/hyperlink-registry.md): numeric identities,
  retention limits, invalidation, and host activation responsibilities.
- [Module context](Module.md): maintainer ownership and test entry points.
