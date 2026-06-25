/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.benchmark

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.core.api.TerminalModeBits
import io.github.ketraterm.input.event.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Benchmarks the input encoding pipeline.
 *
 * Measures throughput and allocation profile for keyboard (legacy + Kitty),
 * mouse (SGR), and paste encoding paths. Uses a counting output sink to
 * verify that bytes are produced without heavyweight I/O.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class TerminalInputBenchmark {
    private lateinit var legacyEncoder: io.github.ketraterm.input.api.TerminalInputEncoder
    private lateinit var kittyEncoder: io.github.ketraterm.input.api.TerminalInputEncoder
    private lateinit var mouseEncoder: io.github.ketraterm.input.api.TerminalInputEncoder

    private lateinit var asciiKeyEvent: TerminalKeyEvent
    private lateinit var specialKeyEvent: TerminalKeyEvent
    private lateinit var modifiedSpecialKeyEvent: TerminalKeyEvent
    private lateinit var mouseEvent: TerminalMouseEvent
    private lateinit var pasteEvent: TerminalPasteEvent

    private val countingSink = CountingHostOutput()

    @Setup(Level.Trial)
    open fun setup() {
        // Legacy mode: all mode bits off.
        legacyEncoder =
            io.github.ketraterm.input.TerminalInputEncoders.create(
                inputState = FixedInputState(0L),
                output = countingSink,
            )

        // Kitty mode: kitty keyboard flags set to 0b11111 (all progressive enhancements).
        val kittyBits =
            TerminalModeBits.withPackedValue(
                0L,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_MASK,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_SHIFT,
                0b11111,
            )
        kittyEncoder =
            io.github.ketraterm.input.TerminalInputEncoders.create(
                inputState = FixedInputState(kittyBits),
                output = countingSink,
            )

        // Mouse encoder: SGR mouse encoding, button tracking.
        val mouseBits =
            TerminalModeBits.withPackedValue(
                TerminalModeBits.withPackedValue(
                    0L,
                    TerminalModeBits.MOUSE_TRACKING_MASK,
                    TerminalModeBits.MOUSE_TRACKING_SHIFT,
                    2, // button event tracking
                ),
                TerminalModeBits.MOUSE_ENCODING_MASK,
                TerminalModeBits.MOUSE_ENCODING_SHIFT,
                2, // SGR encoding
            )
        mouseEncoder =
            io.github.ketraterm.input.TerminalInputEncoders.create(
                inputState = FixedInputState(mouseBits),
                output = countingSink,
            )

        // Pre-built events
        asciiKeyEvent = TerminalKeyEvent.codepoint('a'.code)
        specialKeyEvent = TerminalKeyEvent.key(TerminalKey.UP)
        modifiedSpecialKeyEvent =
            TerminalKeyEvent.key(
                TerminalKey.F5,
                TerminalModifiers.CTRL or TerminalModifiers.SHIFT,
            )
        mouseEvent =
            TerminalMouseEvent(
                column = 40,
                row = 12,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            )
        pasteEvent = TerminalPasteEvent("a".repeat(1024))
    }

    @Setup(Level.Invocation)
    open fun resetCounter() {
        countingSink.reset()
    }

    // -- Legacy keyboard --

    /** Legacy encoding for a printable ASCII key. */
    @Benchmark
    open fun encodeAsciiKeyLegacy(bh: Blackhole) {
        legacyEncoder.encodeKey(asciiKeyEvent)
        bh.consume(countingSink.count)
    }

    /** Legacy encoding for a special key (arrow up). */
    @Benchmark
    open fun encodeSpecialKeyLegacy(bh: Blackhole) {
        legacyEncoder.encodeKey(specialKeyEvent)
        bh.consume(countingSink.count)
    }

    // -- Kitty keyboard --

    /** Kitty protocol encoding for a printable ASCII key. */
    @Benchmark
    open fun encodeAsciiKeyKitty(bh: Blackhole) {
        kittyEncoder.encodeKey(asciiKeyEvent)
        bh.consume(countingSink.count)
    }

    /** Kitty protocol encoding for a modified special key (Ctrl+Shift+F5). */
    @Benchmark
    open fun encodeSpecialKeyKitty(bh: Blackhole) {
        kittyEncoder.encodeKey(modifiedSpecialKeyEvent)
        bh.consume(countingSink.count)
    }

    // -- Mouse --

    /** SGR mouse report encoding. */
    @Benchmark
    open fun encodeMouseSgr(bh: Blackhole) {
        mouseEncoder.encodeMouse(mouseEvent)
        bh.consume(countingSink.count)
    }

    // -- Paste --

    /** Bracketed paste with 1 KB text. */
    @Benchmark
    open fun encodePaste(bh: Blackhole) {
        legacyEncoder.encodePaste(pasteEvent)
        bh.consume(countingSink.count)
    }
}

/** Fixed mode bits for benchmark input state. */
private class FixedInputState(
    private val bits: Long,
) : TerminalInputState {
    override fun getInputModeBits(): Long = bits
}

/** Counting output sink that records the number of bytes written. */
private class CountingHostOutput : io.github.ketraterm.protocol.host.TerminalHostOutput {
    @JvmField
    var count: Int = 0

    fun reset() {
        count = 0
    }

    override fun writeByte(byte: Int) {
        count++
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        count += length
    }

    override fun writeAscii(text: String) {
        count += text.length
    }

    override fun writeUtf8(text: String) {
        var byteLength = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i].code
            if (c <= 0x7F) {
                byteLength += 1
                i++
            } else if (c <= 0x7FF) {
                byteLength += 2
                i++
            } else if (c in 0xD800..0xDBFF && i + 1 < len && text[i + 1].code in 0xDC00..0xDFFF) {
                byteLength += 4
                i += 2
            } else {
                byteLength += 3
                i++
            }
        }
        count += byteLength
    }
}
