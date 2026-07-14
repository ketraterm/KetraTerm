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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalModeSnapshot
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import java.io.ByteArrayOutputStream

/**
 * Deterministic headless parser-to-core conformance harness.
 *
 * The harness wires the production parser, host adapter, core buffer, response
 * channel, and render-frame ABI without a PTY or UI. It is intentionally
 * stateful so callers can snapshot intermediate states while replaying a
 * transcript. Create a fresh instance for independent differential runs.
 *
 * @param columns initial terminal width in cells.
 * @param rows initial terminal height in rows.
 * @param maxHistory maximum retained primary-screen history rows.
 */
class TerminalConformanceHarness
    @JvmOverloads
    constructor(
        columns: Int,
        rows: Int,
        maxHistory: Int = 1000,
    ) {
        private val terminal: TerminalBuffer =
            TerminalBuffers.create(width = columns, height = rows, maxHistory = maxHistory)
        private val adapter = HostCommandAdapter(terminal)
        private val parser: TerminalOutputParser = TerminalParsers.create(adapter)
        private val outboundBytes = ByteArrayOutputStream()
        private val responseScratch = ByteArray(RESPONSE_SCRATCH_SIZE)

        init {
            require(maxHistory >= 0) { "maxHistory must be >= 0, was $maxHistory" }
        }

        /**
         * Applies one replay event and captures any response bytes it produces.
         *
         * @param event exact input, resize, or end-of-input operation.
         */
        fun apply(event: TerminalReplayEvent) {
            when (event) {
                is TerminalReplayEvent.Input -> event.replayWith(parser)
                is TerminalReplayEvent.Resize -> terminal.resize(event.columns, event.rows)
                TerminalReplayEvent.EndOfInput -> parser.endOfInput()
            }
            drainResponses()
        }

        /**
         * Applies every event in [transcript] and returns the resulting snapshot.
         *
         * @param transcript ordered deterministic replay artifact.
         * @return canonical observable state after the final event.
         */
        fun replay(transcript: TerminalReplayTranscript): TerminalConformanceSnapshot {
            for (event in transcript.events) {
                apply(event)
            }
            return snapshot()
        }

        /**
         * Captures retained history, the live grid, cursor, modes, host metadata,
         * and all response bytes emitted since this harness was created.
         *
         * @return immutable snapshot detached from production storage.
         */
        fun snapshot(): TerminalConformanceSnapshot {
            drainResponses()
            val renderReader = terminal as TerminalRenderFrameReader
            val requestedHistory = terminal.historySize
            var result: TerminalConformanceSnapshot? = null

            renderReader.readRenderFrame(
                scrollbackOffset = requestedHistory,
                viewportRows = requestedHistory + terminal.height,
            ) { frame ->
                val columns = frame.columns
                val retainedRows = ArrayList<TerminalRowSnapshot>(frame.rows)
                for (row in 0 until frame.rows) {
                    val codepoints = IntArray(columns)
                    val attributes = LongArray(columns)
                    val extraAttributes = LongArray(columns)
                    val flags = IntArray(columns)
                    val hyperlinkIds = IntArray(columns)
                    val clusters = arrayOfNulls<String>(columns)
                    frame.copyLine(
                        row = row,
                        codeWords = codepoints,
                        attrWords = attributes,
                        flags = flags,
                        extraAttrWords = extraAttributes,
                        hyperlinkIds = hyperlinkIds,
                        clusterSink = { column, text -> clusters[column] = text.toCharArray().concatToString() },
                    )
                    val cells = ArrayList<TerminalCellSnapshot>(columns)
                    for (column in 0 until columns) {
                        cells +=
                            TerminalCellSnapshot(
                                codepoint = codepoints[column],
                                cluster = clusters[column],
                                attributes = attributes[column],
                                extraAttributes = extraAttributes[column],
                                flags = flags[column],
                                hyperlinkId = hyperlinkIds[column],
                            )
                    }
                    retainedRows += TerminalRowSnapshot(wrapped = frame.lineWrapped(row), cells = cells)
                }

                val cursor = frame.cursor
                result =
                    TerminalConformanceSnapshot(
                        columns = columns,
                        visibleRows = terminal.height,
                        historyRows = frame.historySize,
                        discardedRows = frame.discardedCount,
                        liveRowStart = frame.historySize,
                        activeBuffer = frame.activeBuffer.toSnapshotKind(),
                        cursor =
                            TerminalCursorSnapshot(
                                column = terminal.cursorCol,
                                row = terminal.cursorRow,
                                visible = cursor.visible,
                                blinking = cursor.blinking,
                                shape = cursor.shape.name,
                            ),
                        modes = terminal.getModeSnapshot().toConformanceSnapshot(),
                        retainedRows = retainedRows,
                        windowTitle = adapter.windowTitle,
                        iconTitle = adapter.iconTitle,
                        activeHyperlinkUri = adapter.activeHyperlinkUri,
                        activeHyperlinkId = adapter.activeHyperlinkId,
                        outboundBytes = TerminalByteSequence.of(outboundBytes.toByteArray()),
                    )
            }
            return checkNotNull(result) { "render reader did not provide a conformance frame" }
        }

        private fun drainResponses() {
            while (true) {
                val count = terminal.readResponseBytes(responseScratch)
                if (count == 0) {
                    return
                }
                outboundBytes.write(responseScratch, 0, count)
            }
        }

        private fun TerminalRenderBufferKind.toSnapshotKind(): TerminalSnapshotBufferKind =
            when (this) {
                TerminalRenderBufferKind.PRIMARY -> TerminalSnapshotBufferKind.PRIMARY
                TerminalRenderBufferKind.ALTERNATE -> TerminalSnapshotBufferKind.ALTERNATE
            }

        private fun TerminalModeSnapshot.toConformanceSnapshot(): TerminalModeStateSnapshot =
            TerminalModeStateSnapshot(
                insertMode = isInsertMode,
                autoWrap = isAutoWrap,
                applicationCursorKeys = isApplicationCursorKeys,
                applicationKeypad = isApplicationKeypad,
                backarrowKeyModeExplicit = isBackarrowKeyModeExplicit,
                backarrowKeySendsBackspace = isBackarrowKeySendsBackspace,
                originMode = isOriginMode,
                newLineMode = isNewLineMode,
                leftRightMarginMode = isLeftRightMarginMode,
                reverseVideo = isReverseVideo,
                cursorVisible = isCursorVisible,
                cursorBlinking = isCursorBlinking,
                bracketedPaste = isBracketedPasteEnabled,
                focusReporting = isFocusReportingEnabled,
                ambiguousWidthIsWide = treatAmbiguousAsWide,
                mouseTrackingMode = mouseTrackingMode.name,
                mouseEncodingMode = mouseEncodingMode.name,
                modifyOtherKeysMode = modifyOtherKeysMode,
                formatOtherKeysMode = formatOtherKeysMode,
                kittyKeyboardFlags = kittyKeyboardFlags,
                synchronizedOutput = isSynchronizedOutput,
                bellIsUrgent = isBellIsUrgent,
                popOnBell = isPopOnBell,
            )

        private companion object {
            const val RESPONSE_SCRATCH_SIZE = 4096
        }
    }
