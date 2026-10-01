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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Host discovery outside terminal mutation, painting and pointer handling.
 *
 * The binding owner serializes calls within each context and owns their cancellation.
 * A detector declaring both contexts must permit concurrent independent and ordered calls.
 * Implementations may suspend and allocate bounded discovery results, acquire
 * host-read access, and report to the request-confined sink. They must propagate
 * cancellation, discard any tainted ordered state, and never touch Swing state.
 * [configurationGeneration] changes when provider configuration becomes stale.
 */
fun interface SwingHyperlinkDetector {
    /** Fixed dependency discipline for this detector instance. */
    val context: SwingHyperlinkDetectionContext get() = SwingHyperlinkDetectionContext.INDEPENDENT_LINE

    /** Equality-only provider invalidation generation; the owner also tracks instance identity. */
    val configurationGeneration: Long get() = 0L

    /** Binding-owned subscription for provider/index/theme changes even without terminal output. */
    val configurationChanges: Flow<Unit> get() = emptyFlow()

    /**
     * Releases retained ordered source/provider state at a binding or provider boundary.
     * The owner calls this only after the ordered invocation has finished (including cancellation
     * before dispatch). It must be nonblocking, may run on any thread, and must not touch independent state.
     */
    fun discardOrderedState() = Unit

    /**
     * Reports one request's results. Return means successful completion, including
     * an empty result; failure/cancellation must throw and cannot mean empty success.
     * The sink belongs to this call and must not be retained or used by detached work.
     * Ordered state is valid only within the request's binding/source/provider/analysis epochs.
     */
    suspend fun detect(
        request: SwingHyperlinkDetectionRequest,
        sink: SwingHyperlinkDetectionSink,
    )

    companion object {
        /** Successful detector with no results. */
        @JvmField val NONE = SwingHyperlinkDetector { _, _ -> }
    }
}

/** Text dependency and ordering required by a detector. */
enum class SwingHyperlinkDetectionContext {
    /** Each logical line is independent; requests may omit unchanged lines. */
    INDEPENDENT_LINE,

    /**
     * Logical lines are consumed in source order. Results may reference earlier
     * source lines. Continuation/replay is owned by the binding's discovery owner;
     * an epoch change requires fresh state, not continuation of an interrupted call.
     */
    ORDERED_CONTENT,

    /** Separate independently scheduled requests for text-derived and ordered provider results. */
    INDEPENDENT_AND_ORDERED,
}

/**
 * Owned immutable logical text and absolute row anchors for one discovery batch.
 *
 * Soft-wrapped physical rows form a logical line, with one appended newline.
 * UTF-16 cumulative offsets are local to this request (checked against overflow),
 * while absolute row anchors survive append/eviction within a source epoch.
 * The arrays/list are defensively copied. Empty requests are valid.
 * Binding epochs separate resets/rebindings; source epochs separate replacement,
 * reflow and buffer changes; provider epochs separate configuration/instance changes.
 */
class SwingHyperlinkDetectionRequest(
    lineTexts: List<String>,
    firstAbsoluteRows: LongArray,
    lastAbsoluteRows: LongArray = firstAbsoluteRows,
    val context: SwingHyperlinkDetectionContext = SwingHyperlinkDetectionContext.INDEPENDENT_LINE,
    val bindingEpoch: Long = 0L,
    val sourceEpoch: Long = 0L,
    val providerEpoch: Long = 0L,
    /** Changes when earlier content requires ordered state reconstruction. */
    val analysisEpoch: Long = 0L,
    /** Absolute retained boundary; eviction alone does not restart ordered provider state. */
    val firstRetainedRow: Long = 0L,
    firstLineIds: LongArray = firstAbsoluteRows,
) {
    private val lines = lineTexts.toTypedArray()
    private val firstRows = firstAbsoluteRows.copyOf()
    private val lastRows = lastAbsoluteRows.copyOf()
    private val lineIds = firstLineIds.copyOf()
    private val starts = IntArray(lines.size)
    private val ends = IntArray(lines.size)

    init {
        require(firstRows.size == lines.size && lastRows.size == lines.size && lineIds.size == lines.size)
        require(context != SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED)
        require(firstRetainedRow >= 0)
        var offset = 0
        for (index in lines.indices) {
            require(lines[index].endsWith('\n'))
            require(firstRows[index] >= 0 && lastRows[index] >= firstRows[index])
            require(index == 0 || firstRows[index] > lastRows[index - 1])
            starts[index] = offset
            offset = Math.addExact(offset, lines[index].length)
            ends[index] = offset
        }
    }

    /** Number of logical lines in this batch. */
    val lineCount: Int get() = lines.size

    /** Logical text, including the trailing newline. */
    fun lineText(index: Int): String = lines[index]

    /** Inclusive cumulative UTF-16 start within this batch. */
    fun lineStartOffset(index: Int): Int = starts[index]

    /** Exclusive cumulative UTF-16 end within this batch. */
    fun lineEndOffset(index: Int): Int = ends[index]

    /** First physical row of the logical line, in this source epoch's absolute coordinates. */
    fun lineFirstAbsoluteRow(index: Int): Long = firstRows[index]

    /** Last physical row of that same logical line, inclusive. */
    fun lineLastAbsoluteRow(index: Int): Long = lastRows[index]

    /** Stable source line identity for host-owned historical output metadata. */
    fun lineFirstId(index: Int): Long = lineIds[index]

    /** Builds an absolute range from line-local UTF-16 offsets. */
    fun range(
        startLine: Int,
        startOffset: Int,
        endLine: Int,
        endOffset: Int,
    ): SwingHyperlinkTextRange =
        SwingHyperlinkTextRange(
            SwingHyperlinkTextPosition(firstRows[startLine], startOffset),
            SwingHyperlinkTextPosition(firstRows[endLine], endOffset),
        )

    /**
     * Builds a single-line occurrence with a conservative context dependency.
     * Explicit validation bounds must contain the highlight and its delimiters.
     * Ordered defaults depend on the whole supplied batch; specify a narrower
     * dependency only when the action is genuinely independent of surrounding text.
     * The retained owner validates source/validation offsets against its snapshots.
     */
    fun hyperlink(
        lineIndex: Int,
        startOffset: Int,
        endOffset: Int,
        action: SwingHyperlinkAction,
        validationStartOffset: Int = 0,
        validationEndOffset: Int = Int.MAX_VALUE,
        uri: String? = null,
        presentation: SwingHyperlinkPresentation = SwingHyperlinkPresentation.DEFAULT,
        activation: SwingHyperlinkActivation = SwingHyperlinkActivation.MODIFIER,
    ): SwingHyperlink {
        val source = range(lineIndex, startOffset, lineIndex, endOffset)
        val dependency =
            when {
                validationEndOffset != Int.MAX_VALUE -> range(lineIndex, validationStartOffset, lineIndex, validationEndOffset)
                context == SwingHyperlinkDetectionContext.ORDERED_CONTENT -> range(0, 0, lines.lastIndex, lines.last().length)
                else -> range(lineIndex, 0, lineIndex, lines[lineIndex].length)
            }
        return SwingHyperlink(source, dependency, action, uri, presentation, activation)
    }
}

/** Request-confined receiver; results can highlight earlier retained source lines. */
fun interface SwingHyperlinkDetectionSink {
    /** Reports an owned result. Unknown/out-of-bounds source coordinates are ignored by the owner. */
    fun addHyperlink(hyperlink: SwingHyperlink)
}
