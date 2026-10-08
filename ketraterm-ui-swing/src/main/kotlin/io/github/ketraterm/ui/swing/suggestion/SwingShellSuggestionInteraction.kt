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

import io.github.ketraterm.ui.swing.cleanupSwingResources
import io.github.ketraterm.ui.swing.preserveSwingFailure
import java.util.*
import javax.swing.SwingUtilities

/** Why an interaction ended. Only explicit dismissal represents negative user preference. */
public enum class SwingShellSuggestionCloseReason {
    /** The complete edit was admitted or applied. */
    ACCEPTED,

    /** The editing authority declined the acceptance attempt. */
    REJECTED,

    /** The user explicitly dismissed the suggestions. */
    DISMISSED,

    /** Input, shell state, or presentation eligibility invalidated the request. */
    INVALIDATED,

    /** A newer request replaced this interaction. */
    SUPERSEDED,

    /** The owning component was disposed. */
    DISPOSED,

    /** Provider or acceptance work failed. */
    FAILED,

    /** The owner cancelled the request without preference feedback. */
    CANCELLED,
}

/** Synchronous EDT observation of publication, selection, acceptance, and closure. */
public fun interface SwingShellSuggestionInteractionListener {
    /** Reads authoritative state from [interaction]; callbacks may reenter it. */
    public fun onInteractionChanged(interaction: SwingShellSuggestionInteraction)
}

/**
 * One suggestion request shared by built-in, detached Swing, and IntelliJ presentation.
 *
 * Construct and call on the EDT. The owner captures [handler] before asynchronous work,
 * publishes immutable candidate snapshots, and closes obsolete requests. Views own mounting,
 * placement, and visibility; they invoke guarded operations instead of reporting acceptance.
 * Provider completion does not close a still-usable final publication.
 *
 * Acceptance leaves the active state before any view callback. The original handler and
 * feedback destination survive reentrant UI replacement. Callback failures propagate, but
 * cleanup completes and an edit or feedback event is never retried.
 *
 * @property request immutable command-line context captured before provider work.
 * @param handler original editing capability; owned and closed by this interaction.
 * @param feedbackHandler original request observer. Passive closure produces no preference feedback.
 */
public class SwingShellSuggestionInteraction(
    public val request: SwingShellSuggestionRequest,
    private val handler: SwingShellSuggestionHandler = SwingShellSuggestionHandler.NONE,
    feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
) : AutoCloseable {
    private enum class Phase { ACTIVE, ACCEPTING, CLOSED }

    private var phase = Phase.ACTIVE
    private var feedbackHandler = feedbackHandler
    private var listeners: List<SwingShellSuggestionInteractionListener> = emptyList()
    private var revision = 0L
    private var handlerClosed = false
    private var published = false
    private var sourceAttached = false
    internal var contextIsCurrent: (() -> Boolean)? = null

    /** Current complete publication; selection changes reuse its immutable candidate list. */
    public var snapshot: SwingShellSuggestionSnapshot = SwingShellSuggestionSnapshot(this, emptyList(), -1)
        private set

    /** Whether new publications and user actions can still be admitted. */
    public val isActive: Boolean get() = phase == Phase.ACTIVE

    /** Whether final cleanup has run. An accepting interaction is inactive but not yet closed. */
    public val isClosed: Boolean get() = phase == Phase.CLOSED

    /** Final lifecycle outcome, or null while active or synchronously accepting. */
    public var closeReason: SwingShellSuggestionCloseReason? = null
        private set

    init {
        checkEdt()
    }

    /**
     * Copies one progressive ranking at its ownership boundary. Returns false after invalidation.
     * Selection is retained by replacement outcome across reranking when [preserveSelection] is true.
     */
    @JvmOverloads
    public fun publish(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int = -1,
        preserveSelection: Boolean = true,
    ): Boolean {
        checkEdt()
        if (!validateContext()) return false
        require(selectedIndex == -1 || selectedIndex in suggestions.indices) { "selectedIndex must address the publication or be -1" }
        published = true
        val previous = snapshot.selectedSuggestion.takeIf { preserveSelection }
        val copied = Collections.unmodifiableList(ArrayList(suggestions))
        var selection = selectedIndex
        if (previous != null) {
            val retained =
                copied.indexOfFirst {
                    it.replacementStartOffset == previous.replacementStartOffset &&
                        it.replacementEndOffset == previous.replacementEndOffset &&
                        it.replacementText == previous.replacementText
                }
            if (retained >= 0) selection = retained
        }
        snapshot = SwingShellSuggestionSnapshot(this, copied, selection)
        notifyChanged()
        return isActive
    }

    /** Selects an index only if [publication] still belongs to the current candidate publication. */
    public fun select(
        publication: SwingShellSuggestionSnapshot,
        index: Int,
    ): Boolean {
        checkEdt()
        if (!owns(publication) || index !in snapshot.suggestions.indices) return false
        if (snapshot.selectedIndex == index) return true
        snapshot = SwingShellSuggestionSnapshot(this, snapshot.suggestions, index)
        notifyChanged()
        return isActive
    }

    /**
     * Attempts exactly one complete edit using the original capability.
     * A stale view action returns STALE_CONTEXT without closing a newer publication or emitting feedback.
     */
    public fun tryAccept(
        publication: SwingShellSuggestionSnapshot,
        index: Int,
    ): SwingShellSuggestionAcceptanceResult {
        checkEdt()
        if (!owns(publication) || index !in snapshot.suggestions.indices) return SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
        val acceptance = SwingShellSuggestionAcceptance(snapshot.suggestions[index], index, request)
        phase = Phase.ACCEPTING
        try {
            notifyChanged()
            val result = if (handlerClosed) SwingShellSuggestionAcceptanceResult.STALE_CONTEXT else handler.tryAccept(acceptance)
            phase = Phase.CLOSED
            closeReason =
                if (result == SwingShellSuggestionAcceptanceResult.ACCEPTED) {
                    SwingShellSuggestionCloseReason.ACCEPTED
                } else {
                    SwingShellSuggestionCloseReason.REJECTED
                }
            cleanupSwingResources(
                { closeHandler() },
                { notifyChanged() },
                {
                    if (feedbackHandler !== SwingShellSuggestionFeedbackHandler.NONE) {
                        feedbackHandler.onSuggestionFeedback(
                            SwingShellSuggestionFeedback(
                                if (result == SwingShellSuggestionAcceptanceResult.ACCEPTED) {
                                    SwingShellSuggestionFeedbackKind.ACCEPTED
                                } else {
                                    SwingShellSuggestionFeedbackKind.REJECTED
                                },
                                acceptance.suggestion,
                                index,
                                request,
                                result,
                            ),
                        )
                    }
                },
            )
            return result
        } catch (failure: Throwable) {
            if (phase != Phase.CLOSED) {
                phase = Phase.CLOSED
                closeReason = SwingShellSuggestionCloseReason.FAILED
                try {
                    cleanupSwingResources({ closeHandler() }, { notifyChanged() })
                } catch (
                    cleanupFailure: Throwable,
                ) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    /** Explicit user dismissal of the displayed publication. An empty or unselected publication produces no preference feedback. */
    public fun dismiss(publication: SwingShellSuggestionSnapshot): Boolean {
        checkEdt()
        if (!owns(publication)) return false
        val selected = snapshot.selectedSuggestion
        val index = snapshot.selectedIndex
        phase = Phase.CLOSED
        closeReason = SwingShellSuggestionCloseReason.DISMISSED
        cleanupSwingResources(
            { closeHandler() },
            { notifyChanged() },
            {
                if (selected != null && feedbackHandler !== SwingShellSuggestionFeedbackHandler.NONE) {
                    feedbackHandler.onSuggestionFeedback(
                        SwingShellSuggestionFeedback(
                            SwingShellSuggestionFeedbackKind.DISMISSED,
                            selected,
                            index,
                            request,
                        ),
                    )
                }
            },
        )
        return true
    }

    /** Cancels this request without negative user feedback. Cancellation can still reject an in-progress admission. */
    public fun close(reason: SwingShellSuggestionCloseReason) {
        checkEdt()
        if (phase == Phase.CLOSED) return
        if (phase == Phase.ACCEPTING) {
            closeHandler()
            return
        }
        phase = Phase.CLOSED
        closeReason = reason
        cleanupSwingResources({ closeHandler() }, { notifyChanged() })
    }

    /** Cancels an obsolete request and releases its captured editing capability. */
    override fun close(): Unit = close(SwingShellSuggestionCloseReason.CANCELLED)

    /** Registers an EDT observer; registration does not synthesize a state change. */
    public fun addChangeListener(listener: SwingShellSuggestionInteractionListener) {
        checkEdt()
        if (listener !in listeners) listeners = listeners + listener
    }

    /** Removes a registered observer, including from a reentrant callback. */
    public fun removeChangeListener(listener: SwingShellSuggestionInteractionListener) {
        checkEdt()
        listeners = listeners - listener
    }

    internal fun beginSource() {
        checkEdt()
        check(isActive && !published && !sourceAttached) { "one source must be captured before publication" }
        sourceAttached = true
    }

    internal fun attachFeedback(observer: SwingShellSuggestionFeedbackHandler) {
        checkEdt()
        check(isActive && sourceAttached && !published) { "source feedback must be captured before publication" }
        if (observer === SwingShellSuggestionFeedbackHandler.NONE || observer === feedbackHandler) return
        val original = feedbackHandler
        feedbackHandler =
            if (original === SwingShellSuggestionFeedbackHandler.NONE) {
                observer
            } else {
                SwingShellSuggestionFeedbackHandler {
                    cleanupSwingResources({ original.onSuggestionFeedback(it) }, { observer.onSuggestionFeedback(it) })
                }
            }
    }

    private fun owns(publication: SwingShellSuggestionSnapshot): Boolean =
        publication.owner === this && publication.suggestions === snapshot.suggestions && validateContext()

    internal fun validateContext(): Boolean {
        checkEdt()
        if (!isActive) return false
        if (contextIsCurrent?.invoke() == false) {
            close(SwingShellSuggestionCloseReason.INVALIDATED)
            return false
        }
        return true
    }

    private fun notifyChanged() {
        val change = ++revision
        val current = listeners
        var failure: Throwable? = null
        for (listener in current) {
            if (revision != change) break
            if (listener in listeners) {
                try {
                    listener.onInteractionChanged(this)
                } catch (next: Throwable) {
                    failure = preserveSwingFailure(failure, next)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun closeHandler() {
        if (handlerClosed) return
        handlerClosed = true
        handler.close()
    }

    private fun checkEdt() = check(SwingUtilities.isEventDispatchThread()) { "suggestion interaction must run on the EDT" }
}
