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
package io.github.ketraterm.core.graphics

import io.github.ketraterm.core.engine.MutationEngine
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.CLIP_BOTTOM
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.CLIP_TOP
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.COLUMN
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.COLUMNS
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.EFFECTIVE_COLUMNS
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.EFFECTIVE_ROWS
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.OFFSET_X
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.OFFSET_Y
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.ROW
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.ROWS
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.SOURCE_HEIGHT
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.SOURCE_WIDTH
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.SOURCE_X
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.SOURCE_Y
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.STRIDE
import io.github.ketraterm.core.graphics.GraphicsStore.Companion.Z
import io.github.ketraterm.core.state.TerminalState
import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.protocol.TerminalGraphicsResult

/**
 * Applies completed graphics requests on the caller-serialized core mutation thread.
 *
 * The parser owns header syntax, Base64 and transfer assembly. This engine validates
 * image admission and placement against current screen geometry before committing
 * resource replacement, then applies cursor effects and eligible replies. [placement]
 * is reusable command scratch; committed records are copied into [GraphicsStore].
 */
internal class GraphicsEngine(
    private val state: TerminalState,
    private val mutation: MutationEngine,
) {
    private val store get() = state.graphics
    private val placement = IntArray(STRIDE)

    /**
     * Synchronously consumes borrowed controls and the first [length] decoded bytes.
     * An upstream [result] failure bypasses mutation. Uploads allocate immutable ARGB
     * pixels only after bounds and placement validation; queries never retain them.
     */
    fun applyGraphicsCommand(
        command: TerminalGraphicsCommand,
        data: ByteArray,
        length: Int,
        result: TerminalGraphicsResult,
        allowReplies: Boolean,
    ) {
        require(length in 0..data.size)
        val action = command.valueOr('a', 't'.code.toLong()).toInt().toChar()
        val id = command.valueOr('i', 0)
        val number = command.valueOr('I', 0)
        if (action == 'q' && id == 0L) return
        val previous = store.generation
        var replyId = id
        var outcome = result
        if (outcome == TerminalGraphicsResult.OK) {
            outcome = validate(command, action, length)
            if (outcome == TerminalGraphicsResult.OK) {
                when (action) {
                    'd' ->
                        store.delete(
                            state.isAltScreenActive,
                            command.valueOr('d', 'a'.code.toLong()).toInt().toChar(),
                            id,
                            number,
                            command.valueOr('p', 0),
                            command.valueOr('x', 0),
                            command.valueOr('y', 0),
                            command.valueOr('z', 0),
                            state.cursor.col,
                            state.cursor.row,
                        )
                    'p' -> {
                        val slot = store.find(state.isAltScreenActive, id, number)
                        if (slot < 0) {
                            outcome = TerminalGraphicsResult.MISSING
                        } else {
                            val image = store.image(slot)
                            outcome = preparePlacement(command, image.width, image.height)
                            if (outcome == TerminalGraphicsResult.OK &&
                                !store.canPlace(slot, command.valueOr('p', 0), false)
                            ) {
                                outcome = TerminalGraphicsResult.NO_SPACE
                            }
                            if (outcome == TerminalGraphicsResult.OK) {
                                replyId = store.id(slot)
                                place(command, slot)
                            }
                        }
                    }
                    else -> {
                        val w = command.valueOr('s', 0)
                        val h = command.valueOr('v', 0)
                        val format = command.valueOr('f', 32).toInt()
                        // Check each edge before multiplication, including direct core callers.
                        val pixelCount =
                            if (w in 1L..TerminalGraphicsLimits.DIMENSION &&
                                h in 1L..TerminalGraphicsLimits.DIMENSION
                            ) {
                                w * h
                            } else {
                                0L
                            }
                        outcome =
                            when {
                                w <= 0 || h <= 0 -> TerminalGraphicsResult.INVALID
                                w > TerminalGraphicsLimits.DIMENSION ||
                                    h > TerminalGraphicsLimits.DIMENSION ||
                                    pixelCount > TerminalGraphicsLimits.PIXELS -> TerminalGraphicsResult.TOO_LARGE
                                pixelCount * (format / 8) != length.toLong() -> TerminalGraphicsResult.INVALID
                                else -> TerminalGraphicsResult.OK
                            }
                        if (outcome == TerminalGraphicsResult.OK && action == 'T') {
                            outcome = preparePlacement(command, w.toInt(), h.toInt())
                            val old = if (id != 0L) store.find(state.isAltScreenActive, id, 0) else -1
                            if (outcome == TerminalGraphicsResult.OK &&
                                !store.canPlace(old, command.valueOr('p', 0), true)
                            ) {
                                outcome = TerminalGraphicsResult.NO_SPACE
                            }
                        }
                        if (outcome == TerminalGraphicsResult.OK) {
                            val pixels = IntArray(pixelCount.toInt())
                            var source = 0
                            for (i in pixels.indices) {
                                val red = data[source++].toInt() and 255
                                val green = data[source++].toInt() and 255
                                val blue = data[source++].toInt() and 255
                                val alpha = if (format == 32) data[source++].toInt() and 255 else 255
                                pixels[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                            if (action != 'q') {
                                if (number != 0L) replyId = store.allocateId(state.isAltScreenActive)
                                val slot = store.retain(state.isAltScreenActive, replyId, number, ArgbImage(w.toInt(), h.toInt(), pixels))
                                if (action == 'T') place(command, slot)
                            }
                        }
                    }
                }
            }
        }
        if (store.generation != previous) state.markVisualChanged()
        if (action != 'd' &&
            allowReplies &&
            (id != 0L || number != 0L) &&
            command.valueOr('q', 0) != 2L &&
            !(outcome == TerminalGraphicsResult.OK && command.valueOr('q', 0) == 1L)
        ) {
            reply(command, replyId, outcome)
        }
    }

    private fun validate(
        c: TerminalGraphicsCommand,
        action: Char,
        length: Int,
    ): TerminalGraphicsResult {
        if (action !in "tTpqd" ||
            c.valueOr('t', 'd'.code.toLong()) != 'd'.code.toLong() ||
            (c.valueOr('f', 32) != 24L && c.valueOr('f', 32) != 32L) ||
            c.valueOr('o', 0) != 0L ||
            c.valueOr('U', 0) != 0L ||
            c.valueOr('P', 0) != 0L ||
            c.valueOr('Q', 0) != 0L ||
            c.valueOr('H', 0) != 0L ||
            c.valueOr('V', 0) != 0L
        ) {
            return TerminalGraphicsResult.UNSUPPORTED
        }
        if (c.valueOr('i', 0) !in 0L..4294967295L ||
            c.valueOr('I', 0) !in 0L..4294967295L ||
            c.valueOr('p', 0) !in 0L..4294967295L ||
            (c.valueOr('i', 0) != 0L && c.valueOr('I', 0) != 0L) ||
            c.valueOr('C', 0) !in 0L..1L ||
            c.valueOr('q', 0) !in 0L..2L
        ) {
            return TerminalGraphicsResult.INVALID
        }
        if ((action == 'p' || action == 'd') && length != 0) return TerminalGraphicsResult.INVALID
        if (action == 'd' &&
            c.valueOr('d', 'a'.code.toLong()).toInt().toChar() !in "aAiInNrRpPqQcCxXyYzZ"
        ) {
            return TerminalGraphicsResult.UNSUPPORTED
        }
        return TerminalGraphicsResult.OK
    }

    /** Resolves source-pixel crops and cell geometry into scratch without changing retained state. */
    private fun preparePlacement(
        c: TerminalGraphicsCommand,
        width: Int,
        height: Int,
    ): TerminalGraphicsResult {
        val cw = state.windowPixelWidth / state.dimensions.width
        val ch = state.windowPixelHeight / state.dimensions.height
        if (cw <= 0 || ch <= 0) return TerminalGraphicsResult.INVALID
        for (key in "xywhXYcr") if (c.valueOr(key, 0) !in 0L..Int.MAX_VALUE.toLong()) return TerminalGraphicsResult.TOO_LARGE
        if (c.valueOr('z', 0) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return TerminalGraphicsResult.INVALID
        val x = minOf(width, c.valueOr('x', 0).toInt())
        val y = minOf(height, c.valueOr('y', 0).toInt())
        val w = minOf(width - x, c.valueOr('w', 0).toInt().let { if (it == 0) width else it })
        val h = minOf(height - y, c.valueOr('h', 0).toInt().let { if (it == 0) height else it })
        if (w == 0 || h == 0) return TerminalGraphicsResult.INVALID
        placement[COLUMN] = state.cursor.col + if (state.cursor.pendingWrap) 1 else 0
        placement[ROW] = state.cursor.row
        placement[OFFSET_X] = minOf(c.valueOr('X', 0).toInt(), cw - 1)
        placement[OFFSET_Y] = minOf(c.valueOr('Y', 0).toInt(), ch - 1)
        placement[SOURCE_X] = x
        placement[SOURCE_Y] = y
        placement[SOURCE_WIDTH] = w
        placement[SOURCE_HEIGHT] = h
        placement[COLUMNS] = c.valueOr('c', 0).toInt()
        placement[ROWS] = c.valueOr('r', 0).toInt()
        placement[Z] = c.valueOr('z', 0).toInt()
        placement[CLIP_TOP] = Int.MIN_VALUE
        placement[CLIP_BOTTOM] = Int.MAX_VALUE
        if (!GraphicsStore.resolveSpan(placement, 0, cw, ch)) return TerminalGraphicsResult.TOO_LARGE
        return TerminalGraphicsResult.OK
    }

    /** Commits prepared scratch, then applies Kitty's immediate cursor advance unless C=1. */
    private fun place(
        command: TerminalGraphicsCommand,
        slot: Int,
    ) {
        store.put(slot, command.valueOr('p', 0), placement)
        if (command.valueOr('C', 0) == 1L) return
        var column = placement[COLUMN].toLong() + placement[EFFECTIVE_COLUMNS]
        var row = placement[ROW].toLong() + (placement[EFFECTIVE_ROWS] - 1).coerceAtLeast(0)
        val within = row in state.scrollTop.toLong()..state.scrollBottom.toLong()
        if (column >= state.dimensions.width) {
            column = 0
            row++
        }
        if (row > state.scrollBottom) mutation.scrollUp((row - state.scrollBottom).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val top = if (state.modes.isOriginMode && within) state.scrollTop else 0
        val bottom = if (state.modes.isOriginMode && within) state.scrollBottom else state.dimensions.height - 1
        state.cursor.col = column.coerceIn(0, state.dimensions.width.toLong() - 1).toInt()
        state.cursor.row = row.coerceIn(top.toLong(), bottom.toLong()).toInt()
        state.cancelPendingWrap()
        state.markCursorChanged()
    }

    private fun reply(
        command: TerminalGraphicsCommand,
        id: Long,
        result: TerminalGraphicsResult,
    ) {
        val queue = state.hostResponses
        queue.enqueueByte(27)
        queue.enqueueByte('_'.code)
        queue.enqueueByte('G'.code)
        queue.enqueueByte('i'.code)
        queue.enqueueByte('='.code)
        decimal(id)
        val number = command.valueOr('I', 0)
        if (number != 0L) {
            queue.enqueueByte(','.code)
            queue.enqueueByte('I'.code)
            queue.enqueueByte('='.code)
            decimal(number)
        }
        val placementId = command.valueOr('p', 0)
        if (placementId != 0L) {
            queue.enqueueByte(','.code)
            queue.enqueueByte('p'.code)
            queue.enqueueByte('='.code)
            decimal(placementId)
        }
        queue.enqueueByte(';'.code)
        for (char in result.reply) queue.enqueueByte(char.code)
        queue.enqueueByte(27)
        queue.enqueueByte('\\'.code)
    }

    private fun decimal(value: Long) {
        var divisor = 1L
        while (value / divisor >= 10) divisor *= 10
        var remaining = value
        while (divisor > 0) {
            state.hostResponses.enqueueByte('0'.code + (remaining / divisor).toInt())
            remaining %= divisor
            divisor /= 10
        }
    }
}
