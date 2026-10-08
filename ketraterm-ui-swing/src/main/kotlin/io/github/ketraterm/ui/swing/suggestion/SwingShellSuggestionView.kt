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

import io.github.ketraterm.ui.swing.api.SwingTerminalMessages
import java.util.*
import javax.swing.JComponent

/**
 * Immutable viewport published to one shell-suggestion view.
 *
 * The controller retains the complete ranking and sends each renderer only a
 * bounded window. Absolute viewport metadata lets custom-painted and native
 * list implementations expose overflow consistently without duplicating
 * controller state.
 *
 * @property visibleSuggestions ordered suggestions in the current viewport.
 * @property selectedIndex selected index relative to [visibleSuggestions], or
 * `-1` when the popup is passive or selection is outside this viewport.
 * @property viewportStartIndex absolute rank of the first visible suggestion.
 * @property totalSuggestionCount total suggestions retained by the controller.
 */
public class SwingShellSuggestionViewSnapshot private constructor(
    visibleSuggestions: List<SwingShellSuggestion>,
    public val selectedIndex: Int,
    public val viewportStartIndex: Int,
    public val totalSuggestionCount: Int,
    copySuggestions: Boolean = true,
    internal val publication: SwingShellSuggestionSnapshot? = null,
) {
    /** Defensively copied visible suggestion window; Java mutation attempts throw [UnsupportedOperationException]. */
    public val visibleSuggestions: List<SwingShellSuggestion> =
        Collections.unmodifiableList(if (copySuggestions) visibleSuggestions.toList() else visibleSuggestions)

    /** Whether ranked suggestions precede this viewport. */
    public val hasSuggestionsBefore: Boolean
        get() = viewportStartIndex > 0

    /** Whether ranked suggestions follow this viewport. */
    public val hasSuggestionsAfter: Boolean
        get() = viewportStartIndex + visibleSuggestions.size < totalSuggestionCount

    /** Absolute rank of the selected suggestion, or `-1` when none is selected. */
    public val absoluteSelectedIndex: Int
        get() = if (selectedIndex < 0) -1 else viewportStartIndex + selectedIndex

    /** Selected visible suggestion, or `null` when the viewport is passive. */
    public val selectedSuggestion: SwingShellSuggestion?
        get() = visibleSuggestions.getOrNull(selectedIndex)

    init {
        require(selectedIndex == NO_SELECTION || selectedIndex in this.visibleSuggestions.indices) {
            "selectedIndex must be -1 or address visibleSuggestions, was $selectedIndex"
        }
        require(viewportStartIndex >= 0) { "viewportStartIndex must be >= 0, was $viewportStartIndex" }
        require(totalSuggestionCount >= 0) {
            "totalSuggestionCount must be >= 0, was $totalSuggestionCount"
        }
        require(viewportStartIndex <= totalSuggestionCount - this.visibleSuggestions.size) {
            "visible viewport [$viewportStartIndex, ${viewportStartIndex.toLong() + this.visibleSuggestions.size}) " +
                "exceeds totalSuggestionCount $totalSuggestionCount"
        }
        if (this.visibleSuggestions.isEmpty()) {
            require(viewportStartIndex == 0 && totalSuggestionCount == 0) {
                "an empty viewport must use zero start and total counts"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SwingShellSuggestionViewSnapshot &&
            visibleSuggestions == other.visibleSuggestions &&
            selectedIndex == other.selectedIndex &&
            viewportStartIndex == other.viewportStartIndex &&
            totalSuggestionCount == other.totalSuggestionCount

    override fun hashCode(): Int {
        var result = visibleSuggestions.hashCode()
        result = 31 * result + selectedIndex
        result = 31 * result + viewportStartIndex
        result = 31 * result + totalSuggestionCount
        return result
    }

    override fun toString(): String =
        "SwingShellSuggestionViewSnapshot(" +
            "visibleSuggestions=$visibleSuggestions, " +
            "selectedIndex=$selectedIndex, " +
            "viewportStartIndex=$viewportStartIndex, " +
            "totalSuggestionCount=$totalSuggestionCount)"

    public companion object {
        /** Shared empty hidden-view snapshot. */
        @JvmField
        public val EMPTY: SwingShellSuggestionViewSnapshot =
            SwingShellSuggestionViewSnapshot(
                visibleSuggestions = emptyList(),
                selectedIndex = NO_SELECTION,
                viewportStartIndex = 0,
                totalSuggestionCount = 0,
            )

        /**
         * Creates a validated immutable suggestion viewport.
         *
         * @param visibleSuggestions bounded ordered suggestion window.
         * @param selectedIndex selected local index, or `-1`.
         * @param viewportStartIndex absolute rank of the first visible item.
         * @param totalSuggestionCount total retained ranked suggestions.
         * @return a defensive immutable viewport snapshot.
         */
        @JvmStatic
        public fun create(
            visibleSuggestions: List<SwingShellSuggestion>,
            selectedIndex: Int,
            viewportStartIndex: Int,
            totalSuggestionCount: Int,
        ): SwingShellSuggestionViewSnapshot =
            SwingShellSuggestionViewSnapshot(
                visibleSuggestions = visibleSuggestions,
                selectedIndex = selectedIndex,
                viewportStartIndex = viewportStartIndex,
                totalSuggestionCount = totalSuggestionCount,
            )

        internal fun fromPublication(
            publication: SwingShellSuggestionSnapshot,
            start: Int,
            end: Int,
        ): SwingShellSuggestionViewSnapshot =
            SwingShellSuggestionViewSnapshot(
                publication.suggestions.subList(start, end),
                publication.selectedIndex.takeIf { it in start until end }?.minus(start) ?: NO_SELECTION,
                start,
                publication.suggestions.size,
                copySuggestions = false,
                publication = publication,
            )

        private const val NO_SELECTION = -1
    }
}

/**
 * Host-pluggable visual surface for shell suggestions.
 *
 * The interaction owns selection, acceptance, dismissal and feedback; the
 * embedded controller owns navigation and popup placement. A view renders immutable items
 * and reports pointer interaction through the listener used to create it.
 * Implementations must update Swing component state only on the Event Dispatch
 * Thread and must not invoke completion providers or mutate command lines.
 */
public interface SwingShellSuggestionView {
    /** View-owned number of rows used for viewport and page navigation. Must be positive. */
    public val maximumVisibleSuggestions: Int get() = DEFAULT_MAX_VISIBLE_SUGGESTIONS

    /**
     * Component embedded and positioned by the owning Swing terminal.
     */
    public val component: JComponent

    /**
     * Replaces the complete immutable visual viewport on the Swing Event
     * Dispatch Thread.
     *
     * Implementations may retain [snapshot] because its visible suggestions are
     * immutable. They must not reinterpret provider ids or
     * candidate kinds; all renderer semantics are already resolved.
     *
     * @param snapshot authoritative bounded presentation state.
     */
    public fun update(snapshot: SwingShellSuggestionViewSnapshot)

    /**
     * Releases host-specific presentation resources.
     *
     * The default implementation is resource-free. Hosts that own platform
     * editors, popups, listeners, or disposable scopes must release them here.
     * The controller calls this on the EDT even if the final hiding update fails.
     * A close failure propagates, or is suppressed on an earlier hiding failure.
     */
    public fun close(): Unit = Unit

    public companion object {
        /** Standard embedded popup row count; detached views may choose another size. */
        public const val DEFAULT_MAX_VISIBLE_SUGGESTIONS: Int = 8
    }
}

/**
 * Pointer-interaction callback used by a [SwingShellSuggestionView].
 *
 * The view reports the displayed snapshot and local item index. The adapter rejects
 * obsolete gestures before invoking the interaction's guarded actions.
 */
public interface SwingShellSuggestionViewListener {
    /**
     * Reports that the pointer moved over an item.
     *
     * @param index zero-based index in the current visual snapshot.
     */
    public fun onSuggestionHovered(
        snapshot: SwingShellSuggestionViewSnapshot,
        index: Int,
    )

    /**
     * Reports an explicit primary-button click on an item.
     *
     * @param index zero-based index in the current visual snapshot.
     */
    public fun onSuggestionClicked(
        snapshot: SwingShellSuggestionViewSnapshot,
        index: Int,
    )

    /**
     * Requests relative keyboard-style navigation after a pointer-wheel gesture.
     *
     * @param delta negative for earlier suggestions and positive for later
     * suggestions. A zero delta is ignored.
     */
    public fun onSuggestionScrollRequested(
        snapshot: SwingShellSuggestionViewSnapshot,
        delta: Int,
    ): Unit = Unit
}

/**
 * Creates one suggestion view for a reusable Swing terminal instance.
 *
 * Standalone hosts normally use [DEFAULT]. Platform hosts can supply a native
 * Swing implementation without replacing completion behavior.
 */
public fun interface SwingShellSuggestionViewFactory {
    /**
     * Creates a view that reports pointer interaction to [listener].
     *
     * @param listener controller-owned interaction callback.
     * @return a new view owned by one terminal component.
     */
    public fun create(listener: SwingShellSuggestionViewListener): SwingShellSuggestionView

    public companion object {
        /**
         * Factory for KetraTerm's adaptive standalone suggestion surface.
         */
        @JvmField
        public val DEFAULT: SwingShellSuggestionViewFactory =
            SwingShellSuggestionViewFactory { listener -> SwingCompletionPopupView(listener) }

        /**
         * Creates the standard Swing popup factory with host-localized text.
         *
         * @param messages plain-text messages and accessible labels for the host locale.
         * @return factory creating an independent popup for each terminal.
         */
        @JvmStatic
        public fun createDefault(messages: SwingTerminalMessages): SwingShellSuggestionViewFactory =
            SwingShellSuggestionViewFactory { listener -> SwingCompletionPopupView(listener, messages) }
    }
}
