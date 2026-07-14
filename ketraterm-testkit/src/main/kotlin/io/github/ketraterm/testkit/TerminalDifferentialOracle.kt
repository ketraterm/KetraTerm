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
package io.github.ketraterm.testkit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Identity reported by an independent terminal-emulation oracle. */
data class TerminalOracleIdentity(
    /** Stable implementation name. */
    val name: String,
    /** Exact implementation version used for the replay. */
    val version: String,
)

/** Normalized color exposed by an independent terminal oracle. */
data class TerminalOracleColor(
    /** One of `default`, `palette`, or `rgb`. */
    val kind: String,
    /** Palette index, packed RGB value, or zero for the default color. */
    val value: Int,
)

/** Cell state available through the public xterm.js buffer API. */
data class TerminalOracleCellSnapshot(
    /** Complete cell text; empty for blanks and wide trailing cells. */
    val text: String,
    /** Display width reported by the oracle: zero, one, or two cells. */
    val width: Int,
    /** Normalized foreground color. */
    val foreground: TerminalOracleColor,
    /** Normalized background color. */
    val background: TerminalOracleColor,
    /** Whether SGR bold is active. */
    val bold: Boolean,
    /** Whether SGR italic is active. */
    val italic: Boolean,
    /** Whether SGR faint is active. */
    val dim: Boolean,
    /** Whether SGR underline is active. */
    val underline: Boolean,
    /** Whether SGR blink is active. */
    val blink: Boolean,
    /** Whether SGR inverse is active. */
    val inverse: Boolean,
    /** Whether SGR invisible is active. */
    val invisible: Boolean,
    /** Whether SGR strikethrough is active. */
    val strikethrough: Boolean,
    /** Whether SGR overline is active. */
    val overline: Boolean,
)

/** One normalized retained row from an independent oracle. */
data class TerminalOracleRowSnapshot(
    /** Whether this row soft-wraps into the following row. */
    val wrapsToNext: Boolean,
    /** Exactly one cell record per terminal column. */
    val cells: List<TerminalOracleCellSnapshot>,
)

/** Modes exposed by the stable public intersection with xterm.js. */
data class TerminalOracleModeSnapshot(
    val insertMode: Boolean,
    val autoWrap: Boolean,
    val applicationCursorKeys: Boolean,
    val applicationKeypad: Boolean,
    val originMode: Boolean,
    val bracketedPaste: Boolean,
    val focusReporting: Boolean,
    val mouseTrackingMode: String,
    val synchronizedOutput: Boolean,
)

/** Canonical state returned by a process-isolated terminal oracle. */
data class TerminalOracleSnapshot(
    val oracle: TerminalOracleIdentity,
    val columns: Int,
    val visibleRows: Int,
    val historyRows: Int,
    val liveRowStart: Int,
    val activeBuffer: String,
    val cursorColumn: Int,
    val cursorRow: Int,
    val modes: TerminalOracleModeSnapshot,
    val retainedRows: List<TerminalOracleRowSnapshot>,
    val windowTitle: String,
    val outboundBytes: TerminalByteSequence,
)

/** Independent terminal implementation capable of replaying KetraTerm transcripts. */
fun interface TerminalDifferentialOracle {
    /**
     * Replays a transcript from a clean terminal state.
     *
     * @param columns initial width in cells.
     * @param rows initial height in cells.
     * @param maxHistory maximum primary-buffer scrollback requested from the oracle.
     * @param transcript exact ordered input and resize operations.
     * @return normalized state exposed by the oracle.
     */
    fun replay(
        columns: Int,
        rows: Int,
        maxHistory: Int,
        transcript: TerminalReplayTranscript,
    ): TerminalOracleSnapshot
}

/**
 * Versioned JSON-process implementation of [TerminalDifferentialOracle].
 *
 * A fresh process is used for every replay, preventing parser or mode state
 * from leaking between cases. Output and diagnostics are drained concurrently
 * with explicit size limits so a defective oracle cannot deadlock the test JVM.
 *
 * @param command executable and arguments used to start the oracle.
 * @param workingDirectory process working directory, or `null` to inherit it.
 * @param timeout maximum wall-clock duration of one replay.
 */
class TerminalProcessOracle(
    command: List<String>,
    private val workingDirectory: Path? = null,
    private val timeout: Duration = Duration.ofSeconds(10),
) : TerminalDifferentialOracle {
    private val command = command.toList()

    init {
        require(command.isNotEmpty()) { "oracle command must not be empty" }
        require(timeout > Duration.ZERO) { "timeout must be positive, was $timeout" }
    }

    override fun replay(
        columns: Int,
        rows: Int,
        maxHistory: Int,
        transcript: TerminalReplayTranscript,
    ): TerminalOracleSnapshot {
        require(columns in 1..MAX_DIMENSION) { "columns must be in 1..$MAX_DIMENSION, was $columns" }
        require(rows in 1..MAX_DIMENSION) { "rows must be in 1..$MAX_DIMENSION, was $rows" }
        require(maxHistory in 0..MAX_HISTORY) { "maxHistory must be in 0..$MAX_HISTORY, was $maxHistory" }

        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(it.toFile()) }
        val process = builder.start()
        val stdout = BoundedStreamCollector(process.inputStream, MAX_OUTPUT_BYTES)
        val stderr = BoundedStreamCollector(process.errorStream, MAX_DIAGNOSTIC_BYTES)
        val stdoutThread = Thread.ofVirtual().start(stdout)
        val stderrThread = Thread.ofVirtual().start(stderr)

        process.outputStream.use { stream ->
            MAPPER.writeValue(stream, request(columns, rows, maxHistory, transcript))
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw IllegalStateException("terminal oracle timed out after $timeout: ${command.joinToString(" ")}")
        }
        stdoutThread.join()
        stderrThread.join()
        check(!stdout.overflowed) { "terminal oracle stdout exceeded $MAX_OUTPUT_BYTES bytes" }
        check(!stderr.overflowed) { "terminal oracle stderr exceeded $MAX_DIAGNOSTIC_BYTES bytes" }
        val diagnostics = stderr.text()
        check(process.exitValue() == 0) {
            "terminal oracle exited with ${process.exitValue()}: ${diagnostics.ifBlank { "no diagnostics" }}"
        }
        return parseSnapshot(MAPPER.readTree(stdout.bytes()))
    }

    private fun request(
        columns: Int,
        rows: Int,
        maxHistory: Int,
        transcript: TerminalReplayTranscript,
    ): ObjectNode =
        MAPPER.createObjectNode().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("columns", columns)
            put("rows", rows)
            put("maxHistory", maxHistory)
            set<ArrayNode>(
                "events",
                MAPPER.createArrayNode().also { events ->
                    for (event in transcript.events) {
                        events.add(
                            MAPPER.createObjectNode().apply {
                                when (event) {
                                    is TerminalReplayEvent.Input -> {
                                        put("type", "input")
                                        put("hex", TerminalByteSequence.of(event.copyBytes()).toHexString())
                                    }
                                    is TerminalReplayEvent.Resize -> {
                                        put("type", "resize")
                                        put("columns", event.columns)
                                        put("rows", event.rows)
                                    }
                                    TerminalReplayEvent.EndOfInput -> put("type", "endOfInput")
                                }
                            },
                        )
                    }
                },
            )
        }

    private fun parseSnapshot(root: JsonNode): TerminalOracleSnapshot {
        require(root.requiredInt("protocolVersion") == PROTOCOL_VERSION) { "unsupported oracle protocol version" }
        val columns = root.requiredInt("columns")
        val visibleRows = root.requiredInt("visibleRows")
        val historyRows = root.requiredInt("historyRows")
        val liveRowStart = root.requiredInt("liveRowStart")
        val rowNodes = root.requiredArray("retainedRows")
        val retainedRows =
            rowNodes.map { row ->
                TerminalOracleRowSnapshot(
                    wrapsToNext = row.requiredBoolean("wrapsToNext"),
                    cells = row.requiredArray("cells").map(::parseCell),
                )
            }
        require(columns > 0 && visibleRows > 0 && historyRows >= 0) { "oracle returned invalid dimensions" }
        require(liveRowStart == historyRows) { "oracle liveRowStart must equal historyRows" }
        require(retainedRows.size == historyRows + visibleRows) { "oracle returned an inconsistent retained row count" }
        require(retainedRows.all { it.cells.size == columns }) { "oracle returned an inconsistent cell count" }

        val identity = root.requiredObject("oracle")
        val cursor = root.requiredObject("cursor")
        val modes = root.requiredObject("modes")
        return TerminalOracleSnapshot(
            oracle = TerminalOracleIdentity(identity.requiredText("name"), identity.requiredText("version")),
            columns = columns,
            visibleRows = visibleRows,
            historyRows = historyRows,
            liveRowStart = liveRowStart,
            activeBuffer = root.requiredText("activeBuffer"),
            cursorColumn = cursor.requiredInt("column"),
            cursorRow = cursor.requiredInt("row"),
            modes =
                TerminalOracleModeSnapshot(
                    insertMode = modes.requiredBoolean("insertMode"),
                    autoWrap = modes.requiredBoolean("autoWrap"),
                    applicationCursorKeys = modes.requiredBoolean("applicationCursorKeys"),
                    applicationKeypad = modes.requiredBoolean("applicationKeypad"),
                    originMode = modes.requiredBoolean("originMode"),
                    bracketedPaste = modes.requiredBoolean("bracketedPaste"),
                    focusReporting = modes.requiredBoolean("focusReporting"),
                    mouseTrackingMode = modes.requiredText("mouseTrackingMode"),
                    synchronizedOutput = modes.requiredBoolean("synchronizedOutput"),
                ),
            retainedRows = retainedRows,
            windowTitle = root.requiredText("windowTitle"),
            outboundBytes = TerminalByteSequence.of(root.requiredText("outboundHex").decodeHex()),
        )
    }

    private fun parseCell(node: JsonNode): TerminalOracleCellSnapshot =
        TerminalOracleCellSnapshot(
            text = node.requiredText("text"),
            width = node.requiredInt("width"),
            foreground = node.requiredObject("foreground").toColor(),
            background = node.requiredObject("background").toColor(),
            bold = node.requiredBoolean("bold"),
            italic = node.requiredBoolean("italic"),
            dim = node.requiredBoolean("dim"),
            underline = node.requiredBoolean("underline"),
            blink = node.requiredBoolean("blink"),
            inverse = node.requiredBoolean("inverse"),
            invisible = node.requiredBoolean("invisible"),
            strikethrough = node.requiredBoolean("strikethrough"),
            overline = node.requiredBoolean("overline"),
        )

    private fun JsonNode.toColor() = TerminalOracleColor(requiredText("kind"), requiredInt("value"))

    private class BoundedStreamCollector(
        private val input: java.io.InputStream,
        private val limit: Int,
    ) : Runnable {
        private val output = ByteArrayOutputStream()
        var overflowed: Boolean = false
            private set

        override fun run() {
            input.use {
                val scratch = ByteArray(8192)
                while (true) {
                    val count = it.read(scratch)
                    if (count < 0) return
                    val remaining = limit - output.size()
                    if (remaining > 0) output.write(scratch, 0, minOf(count, remaining))
                    if (count > remaining) overflowed = true
                }
            }
        }

        fun bytes(): ByteArray = output.toByteArray()

        fun text(): String = output.toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_DIMENSION = 4096
        const val MAX_HISTORY = 1_000_000
        const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024
        const val MAX_DIAGNOSTIC_BYTES = 1024 * 1024
        val MAPPER = ObjectMapper()
    }
}

private fun JsonNode.requiredObject(name: String): JsonNode =
    required(name).also { require(it.isObject) { "oracle field '$name' must be an object" } }

private fun JsonNode.requiredArray(name: String): JsonNode =
    required(name).also { require(it.isArray) { "oracle field '$name' must be an array" } }

private fun JsonNode.requiredText(name: String): String =
    required(name).also { require(it.isTextual) { "oracle field '$name' must be text" } }.textValue()

private fun JsonNode.requiredInt(name: String): Int =
    required(name).also { require(it.isInt) { "oracle field '$name' must be an integer" } }.intValue()

private fun JsonNode.requiredBoolean(name: String): Boolean =
    required(name).also { require(it.isBoolean) { "oracle field '$name' must be boolean" } }.booleanValue()

private fun JsonNode.required(name: String): JsonNode =
    get(name) ?: throw IllegalArgumentException("oracle response omitted required field '$name'")

private fun String.decodeHex(): ByteArray {
    require(length % 2 == 0 && all { it.digitToIntOrNull(16) != null }) { "oracle returned invalid hexadecimal bytes" }
    return ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(16) shl 4) or this[index * 2 + 1].digitToInt(16)).toByte()
    }
}
