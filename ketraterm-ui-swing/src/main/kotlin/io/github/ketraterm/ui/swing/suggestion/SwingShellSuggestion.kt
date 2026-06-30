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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import java.text.BreakIterator

/**
 * Host-provided shell suggestion shown by the reusable Swing terminal popup.
 *
 * The Swing layer only presents and selects suggestions. It does not decide how
 * accepted text should replace the command line because shell editing semantics
 * belong to the host/provider that produced the suggestion.
 *
 * @property replacementText text the provider intends to insert or use for
 * command-line replacement after acceptance.
 * @property displayText primary text shown in the popup.
 * @property detail secondary text shown below [displayText], such as flags,
 * path context, or a short description.
 * @property source compact source label, such as `history`, `path`, or `git`.
 * @property deleteCount number of characters/grapheme clusters to delete before
 * inserting the suggestion when no explicit replacement range is set. `-1`
 * triggers default prefix deletion based on cursor offset.
 * @property replacementStartOffset inclusive UTF-16 start offset in the request
 * command text for range-aware replacement, or `-1` when unset.
 * @property replacementEndOffset exclusive UTF-16 end offset in the request
 * command text for range-aware replacement, or `-1` when unset.
 */
data class SwingShellSuggestion
    @JvmOverloads
    constructor(
        val replacementText: String,
        val displayText: String = replacementText,
        val detail: String = "",
        val source: String = "",
        val deleteCount: Int = -1,
        val replacementStartOffset: Int = -1,
        val replacementEndOffset: Int = -1,
    ) {
        init {
            require(replacementText.isNotEmpty()) { "replacementText must not be empty" }
            require(displayText.isNotEmpty()) { "displayText must not be empty" }
            require(deleteCount >= -1) { "deleteCount must be >= -1, was $deleteCount" }
            require(
                (replacementStartOffset == -1 && replacementEndOffset == -1) ||
                    (replacementStartOffset >= 0 && replacementEndOffset >= replacementStartOffset),
            ) {
                "replacement range must be unset or satisfy 0 <= start <= end, was " +
                    "$replacementStartOffset..$replacementEndOffset"
            }
        }
    }

/**
 * Snapshot of the currently visible shell suggestion popup state.
 *
 * @property visible whether the popup is currently visible.
 * @property count number of retained suggestions.
 * @property selectedIndex selected suggestion index, or `-1` when none.
 * @property anchorColumn terminal-grid column used as the popup anchor.
 * @property anchorRow terminal-grid row used as the popup anchor.
 * @property selectedSuggestion selected suggestion, or `null` when no
 * suggestion is selected.
 */
data class SwingShellSuggestionState(
    val visible: Boolean,
    val count: Int,
    val selectedIndex: Int,
    val anchorColumn: Int,
    val anchorRow: Int,
    val selectedSuggestion: SwingShellSuggestion?,
) {
    companion object {
        /**
         * Empty state used when the popup is hidden.
         */
        @JvmField
        val EMPTY: SwingShellSuggestionState =
            SwingShellSuggestionState(
                visible = false,
                count = 0,
                selectedIndex = -1,
                anchorColumn = 0,
                anchorRow = 0,
                selectedSuggestion = null,
            )
    }
}

/**
 * Immutable command-line context used to request shell suggestions.
 *
 * @property commandText visible command-line text known to the provider.
 * @property cursorOffset UTF-16 cursor offset within [commandText].
 * @property anchorColumn terminal-grid column used as the popup anchor.
 * @property anchorRow terminal-grid row used as the popup anchor.
 */
data class SwingShellSuggestionRequest(
    val commandText: String,
    val cursorOffset: Int,
    val anchorColumn: Int,
    val anchorRow: Int,
) {
    init {
        require(cursorOffset in 0..commandText.length) {
            "cursorOffset must be in 0..${commandText.length}, was $cursorOffset"
        }
        require(anchorColumn >= 0) { "anchorColumn must be >= 0, was $anchorColumn" }
        require(anchorRow >= 0) { "anchorRow must be >= 0, was $anchorRow" }
    }

    companion object {
        /**
         * Empty request used by direct popup callers that already computed the
         * suggestion list outside the provider pipeline.
         */
        @JvmField
        val EMPTY: SwingShellSuggestionRequest =
            SwingShellSuggestionRequest(
                commandText = "",
                cursorOffset = 0,
                anchorColumn = 0,
                anchorRow = 0,
            )
    }
}

/**
 * Host/provider result accepted by the user.
 *
 * @property suggestion accepted suggestion.
 * @property index index of [suggestion] in the displayed list.
 * @property request command-line context that produced the accepted suggestion.
 */
data class SwingShellSuggestionAcceptance(
    val suggestion: SwingShellSuggestion,
    val index: Int,
    val request: SwingShellSuggestionRequest,
)

/**
 * Host provider for event-level shell suggestions.
 */
fun interface SwingShellSuggestionProvider {
    /**
     * Returns suggestions for [request].
     *
     * This method is invoked on the Swing Event Dispatch Thread. Providers must
     * keep the work bounded and return quickly; slower providers should cache or
     * precompute outside this callback and return the latest ready snapshot.
     *
     * @param request command-line context.
     * @return ordered suggestions, best first.
     */
    fun suggestions(request: SwingShellSuggestionRequest): List<SwingShellSuggestion>

    companion object {
        /**
         * Provider that returns no suggestions.
         */
        @JvmField
        val NONE: SwingShellSuggestionProvider = SwingShellSuggestionProvider { emptyList() }
    }
}

/**
 * Host callback invoked when the user accepts a shell suggestion.
 */
fun interface SwingShellSuggestionHandler {
    /**
     * Handles an accepted suggestion.
     *
     * @param acceptance accepted suggestion and the request that produced it.
     */
    fun onSuggestionAccepted(acceptance: SwingShellSuggestionAcceptance)

    companion object {
        /**
         * Handler that ignores accepted suggestions.
         */
        @JvmField
        val NONE: SwingShellSuggestionHandler = SwingShellSuggestionHandler { }

        /**
         * Creates a standard command-line replacement suggestion handler.
         *
         * The default handler is Unicode-aware: it computes grapheme-cluster
         * counts for generated Delete and Backspace events before pasting the
         * accepted suggestion replacement.
         *
         * @param session active input encoder used to write backspaces and paste events.
         * @return standard replacement suggestion handler.
         */
        @JvmStatic
        fun createDefault(session: TerminalInputEncoder): SwingShellSuggestionHandler =
            SwingShellSuggestionHandler { acceptance ->
                val request = acceptance.request
                val suggestion = acceptance.suggestion

                if (suggestion.hasReplacementRange()) {
                    if (!suggestion.hasValidReplacementRangeFor(request)) {
                        return@SwingShellSuggestionHandler
                    }
                    val deleteAfterCursor =
                        countGraphemeClusters(
                            request.commandText.substring(request.cursorOffset, suggestion.replacementEndOffset),
                        )
                    repeat(deleteAfterCursor) {
                        session.encodeKey(TerminalKeyEvent.key(TerminalKey.DELETE))
                    }

                    val deleteBeforeCursor =
                        countGraphemeClusters(
                            request.commandText.substring(suggestion.replacementStartOffset, request.cursorOffset),
                        )
                    repeat(deleteBeforeCursor) {
                        session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                    }
                } else {
                    val toDelete =
                        if (suggestion.deleteCount >= 0) {
                            suggestion.deleteCount
                        } else {
                            countGraphemeClusters(request.commandText.substring(0, request.cursorOffset))
                        }

                    repeat(toDelete) {
                        session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                    }
                }
                session.encodePaste(TerminalPasteEvent(suggestion.replacementText))
            }

        private fun SwingShellSuggestion.hasReplacementRange(): Boolean = replacementStartOffset >= 0 || replacementEndOffset >= 0

        private fun SwingShellSuggestion.hasValidReplacementRangeFor(request: SwingShellSuggestionRequest): Boolean =
            replacementStartOffset >= 0 &&
                replacementStartOffset <= request.cursorOffset &&
                request.cursorOffset <= replacementEndOffset &&
                replacementEndOffset <= request.commandText.length &&
                request.commandText.isUtf16Boundary(replacementStartOffset) &&
                request.commandText.isUtf16Boundary(request.cursorOffset) &&
                request.commandText.isUtf16Boundary(replacementEndOffset)

        private fun String.isUtf16Boundary(offset: Int): Boolean {
            if (offset !in 0..length) return false
            val afterHighSurrogate = offset > 0 && Character.isHighSurrogate(this[offset - 1])
            val beforeLowSurrogate = offset < length && Character.isLowSurrogate(this[offset])
            return !afterHighSurrogate && !beforeLowSurrogate
        }

        private fun countGraphemeClusters(text: String): Int {
            if (text.isEmpty()) return 0
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(text)
            var count = 0
            while (iterator.next() != BreakIterator.DONE) {
                count++
            }
            return count
        }
    }
}
