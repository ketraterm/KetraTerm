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
package consumer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.*
import io.github.ketraterm.render.api.TerminalColorPalette

private class KotlinReader : TerminalReader {
    override val palette = TerminalColorPalette()
    override val isAlternateScreenActive = false
    override val width = 1
    override val height = 1
    override val windowTitle = "Kotlin"
    override val iconTitle = ""
    override val cursorCol = 0
    override val cursorRow = 0
    override val historySize = 0
    private val line =
        object : TerminalLine {
            override val width = 1

            override fun getCodepoint(col: Int) = if (col == 0) 'K'.code else 0
        }
    private val empty = JavaConsumer.Line(0)

    override fun getLine(row: Int): TerminalLine = if (row == 0) line else empty

    override fun getCodepointAt(
        col: Int,
        row: Int,
    ) = if (row == 0) line.getCodepoint(col) else 0
}

fun main() {
    JavaConsumer.verify()
    val reader = KotlinReader()
    check(reader.getCodepointAt(0, 0) == 'K'.code && reader.getCodepointAt(Int.MIN_VALUE, 0) == 0)
    check(reader.getLine(Int.MAX_VALUE).width == 0 && reader.getLine(0).readCluster(0, IntArray(0)) == 0)
    check(reader.getLine(0).getClusterLength(0) == 0)
    val buffer = TerminalBuffers.create(2, 1, 0)
    val expected = IntArray(4097) { if (it == 0) 'e'.code else 0x0301 }
    buffer.writeCluster(expected, expected.size)
    val line = buffer.getLine(0)
    val required = line.getClusterLength(0)
    check(required == expected.size && line.getClusterLength(1) == 0)
    val directCopy = IntArray(required)
    check(line.readCluster(0, directCopy) == required && directCopy.contentEquals(expected))
    JavaConsumer.verifyCluster(line, expected)
    val tooSmall = intArrayOf(-1)
    try {
        buffer.getLine(0).readCluster(0, tooSmall)
        error("Incomplete destination accepted")
    } catch (
        _: IndexOutOfBoundsException,
    ) {
        check(tooSmall[0] == -1)
    }
    var ownedCopy: IntArray? = null
    buffer.readRenderFrame { frame ->
        frame.copyLine(0, IntArray(2), attrWords = LongArray(2), flags = IntArray(2), clusterDataSink = { column, data, offset, length ->
            check(column == 0)
            ownedCopy = data.copyOfRange(offset, offset + length)
        })
    }
    check(checkNotNull(ownedCopy).contentEquals(expected))
    buffer.eraseBuffer()
    check(buffer.getCodepointAt(0, 0) == 0)
    JavaConsumer.erase(buffer)
}
