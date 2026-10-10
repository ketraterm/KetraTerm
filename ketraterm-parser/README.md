# KetraTerm Parser (`:ketraterm-parser`)

Parses terminal host output into synchronous `TerminalCommandSink` callbacks.
The parser owns UTF-8 decoding, ANSI sequence recognition, charset mapping, and
grapheme assembly. The sink owns terminal state, cell width, and grid behavior.
The only library dependency is `ketraterm-protocol`.

See the [repository README](../README.md) for installation and JVM requirements.

Use this module when supplying a command sink or replacing the parsing layer.
For a complete terminal pipeline, use [ketraterm-session](../ketraterm-session);
[ketraterm-host](../ketraterm-host) supplies the parser-to-core adapter.

## How to Use

Create one parser per stream and feed bytes in their original order:

```kotlin
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.spi.TerminalCommandSink

class ParserConsumer(sink: TerminalCommandSink) {
    private val parser = TerminalParsers.create(sink)

    fun onDataReceived(buffer: ByteArray, bytesRead: Int) {
        parser.accept(buffer, 0, bytesRead)
    }

    fun onEndOfStream() {
        parser.endOfInput()
    }
}
```

`accept` consumes the specified range before returning; the caller can then reuse
the byte array. Chunks may split UTF-8 scalars, escape sequences, control strings,
or graphemes. Calls, including `reset` and `endOfInput`, must be serialized and
non-reentrant. The parser starts no threads or coroutines. Callbacks execute on
the calling thread, so they should return promptly.

`endOfInput` publishes pending printable text, replaces incomplete printable
UTF-8, and discards unfinished OSC/DCS commands. `reset` discards parser-owned
state without emitting a terminal reset. If a callback throws, the exception
propagates; stop processing that stream because parser and sink state may no
longer agree.

## How to Implement: Custom Command Sink

Implement [TerminalCommandSink](src/main/kotlin/io/github/ketraterm/parser/spi/TerminalCommandSink.kt)
to receive semantic operations. Its contracts matter beyond individual commands:

- `writeCluster` and `updatePreviousCluster` borrow an `IntArray` for the callback
  only. Consume or copy the used prefix before returning.
- `updatePreviousCluster` supplies the complete retained grapheme, including its
  previously published prefix. The sink updates the same text target and owns
  attributes, width, cursor movement, and wrapping.
- `isAlternateScreenActive` must reflect the effective screen synchronously,
  including rejected or repeated mode requests. Parser-owned charset saves use
  this observation.
- [TerminalAsciiCommandSink](src/main/kotlin/io/github/ketraterm/parser/spi/TerminalAsciiCommandSink.kt)
  is an optional batching capability. Its ASCII slices are also borrowed;
  ordinary sinks continue to receive scalar callbacks.

The factory retains at most 32 codepoints per grapheme. Additional continuations
advance segmentation context without being emitted. Read boundaries publish
printable prefixes while allowing later continuations to update them; publication
does not promise identical grid placement for every chunking of a width-changing
grapheme. See [grapheme publication and retention](docs/grapheme-segmentation.md).

## Custom OSC Commands

`TerminalParsers.create` overloads accept a `TerminalCustomOscHandler` for complete
OSC commands the built-in parser does not support. The handler receives the
command number and a borrowed byte range containing the undecoded body. It owns
validation, decoding, permissions, and any retained copy.

The handler receives only complete, valid, bounded envelopes. Built-in commands
never fall through. Configure the custom OSC size limit through the factory.

For session integration, use `TerminalOutputParserFactory` to preserve the
session's assembled sink and live clipboard collection budget. That budget bounds
OSC 52 collection; it does not grant permission to modify the clipboard. Custom
handlers running under a session must not wait for UI work or call mutating
session APIs.

## Sub-Documentation

- [Module overview](Module.md): implementation boundaries and maintenance entry points.
- [ANSI FSM specification](docs/ansi-fsm-specification.md): routing, dispatch keys, and recovery.
- [Grapheme segmentation](docs/grapheme-segmentation.md): UTF-8, publication, retention, and charset state.
- [Feature map](../docs/terminal-feature-map.md) and [gap map](../docs/terminal-feature-gap-map.md): supported behavior and deferred scope.
