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
package io.github.ketraterm.ui.swing.api

/**
 * Host-provided visible-viewport hyperlink detector.
 *
 * The reusable Swing terminal calls this outside painting and mouse movement,
 * after visible render-cache text has been snapshotted. Implementations may
 * allocate and may call host/IDE link-discovery APIs, but must not touch Swing
 * component state directly. Reported offsets are UTF-16 offsets within the
 * supplied line text.
 */
fun interface SwingHyperlinkDetector {
    /**
     * Detects hyperlinks in [request] and reports them to [sink].
     *
     * Implementations are called on a background worker owned by the Swing
     * terminal. Host frameworks that require read locks or application
     * dispatching should acquire them inside this method.
     *
     * @param request immutable visible-viewport text snapshot.
     * @param sink receiver for detected ranges and activation actions.
     */
    fun detect(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
    )

    companion object {
        /**
         * Detector that reports no links.
         */
        @JvmField
        val NONE: SwingHyperlinkDetector = SwingHyperlinkDetector { _, _ -> }
    }
}

/**
 * Immutable visible terminal text snapshot passed to [SwingHyperlinkDetector].
 *
 * Lines are logical terminal lines: soft-wrapped render rows are joined, and a
 * line separator is appended to each line to match IntelliJ-style console
 * filter contracts. Offsets returned by [lineStartOffset] and [lineEndOffset]
 * are cumulative UTF-16 offsets across all lines in this request.
 */
class SwingHyperlinkDetectionRequest internal constructor(
    private val lines: Array<String>,
    private val lineStartOffsets: IntArray,
    private val lineEndOffsets: IntArray,
) {
    /**
     * Number of logical lines in this visible snapshot.
     */
    val lineCount: Int
        get() = lines.size

    /**
     * Returns the logical line text at [index].
     *
     * @param index zero-based logical line index.
     * @return line text, including a trailing line separator.
     */
    fun lineText(index: Int): String = lines[index]

    /**
     * Returns the cumulative UTF-16 start offset for logical line [index].
     *
     * @param index zero-based logical line index.
     * @return inclusive line start offset.
     */
    fun lineStartOffset(index: Int): Int = lineStartOffsets[index]

    /**
     * Returns the cumulative UTF-16 end offset for logical line [index].
     *
     * @param index zero-based logical line index.
     * @return exclusive line end offset.
     */
    fun lineEndOffset(index: Int): Int = lineEndOffsets[index]
}

/**
 * Receives detected visible-viewport hyperlink ranges.
 */
interface SwingHyperlinkDetectionSink {
    /**
     * Adds a detected hyperlink.
     *
     * @param lineIndex logical line index from the detection request.
     * @param startOffset inclusive UTF-16 offset within the line text.
     * @param endOffset exclusive UTF-16 offset within the line text.
     * @param action host-owned action invoked after explicit user activation.
     */
    fun addHyperlink(
        lineIndex: Int,
        startOffset: Int,
        endOffset: Int,
        action: SwingHyperlinkAction,
    )
}
