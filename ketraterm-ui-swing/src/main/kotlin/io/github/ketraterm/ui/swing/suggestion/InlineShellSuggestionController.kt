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

import io.github.ketraterm.session.TerminalCommandEditContext
import io.github.ketraterm.session.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import java.awt.event.KeyEvent

/** Independent inline request ownership, using the shared publication/edit/feedback contract. */
internal class InlineShellSuggestionController(
    private val scope: CoroutineScope,
    private val sourceDispatcher: CoroutineDispatcher,
    private val session: () -> TerminalSession?,
    private val eligible: () -> Boolean,
    private val present: (TerminalCommandEditContext, String) -> Boolean,
    private val hide: () -> Unit,
    private val visible: () -> Boolean,
    private val popupCommand: () -> String?,
    private val popupOwnsInput: () -> Boolean,
    private val failure: (SwingShellSuggestionInteraction, Exception) -> Unit,
) {
    private var active: SwingShellSuggestionInteraction? = null
    private var source: SwingInlineShellSuggestionProvider? = null
    private var sourceJob: Job? = null
    private var observation: Job? = null
    private var continuation: Job? = null
    private var sourceRevision = 0L
    val revision get() = sourceRevision
    private var selectedPopup: String? = null
    private var feedbackForPublication: ((SwingShellSuggestionFeedbackHandler) -> Unit)? = null
    private var currentFeedback: SwingShellSuggestionFeedbackHandler? = null
    var coexistence = SwingInlineShellSuggestionCoexistence.INDEPENDENT
        set(value) {
            field = value
            popupChanged()
        }
    var keymap: SwingShellSuggestionKeymap = SwingShellSuggestionKeymap.INLINE_STANDARD
    val publication get() = active?.takeIf { it.isActive }?.snapshot
    var isAutomatic: Boolean = false
        private set

    fun isRequestCurrent(): Boolean =
        continuation?.isActive == true || active?.let { it.isActive && it.contextIsCurrent?.invoke() != false } == true

    fun begin(
        feedback: SwingShellSuggestionFeedbackHandler,
        automatic: Boolean = false,
        confirmed: TerminalCommandEditContext? = null,
    ): SwingShellSuggestionInteraction? {
        if (automatic && !isAutomatic && isRequestCurrent()) return null
        val revision = sourceRevision + 1
        close(SwingShellSuggestionCloseReason.SUPERSEDED)
        if (sourceRevision != revision || active != null) return null
        val bound = session() ?: return null
        if (!eligible()) return null
        val expected = confirmed ?: bound.captureCommandEdit() ?: return null
        val line = expected.commandLine
        if (line.requiresShellConfirmation || line.cursorOffset != line.commandText.length) {
            expected.cancel()
            return null
        }
        val request = SwingShellSuggestionRequest(line.commandText, line.cursorOffset)
        val handler = SwingShellSuggestionEditTarget.captureSessionEdit(bound, request, expected) ?: return null
        var sourceFeedback = SwingShellSuggestionFeedbackHandler.NONE
        val combinedFeedback =
            if (confirmed != null) {
                feedback
            } else {
                SwingShellSuggestionFeedbackHandler { event ->
                    val observer = sourceFeedback
                    io.github.ketraterm.ui.swing.cleanupSwingResources(
                        { feedback.onSuggestionFeedback(event) },
                        { observer.onSuggestionFeedback(event) },
                    )
                }
            }
        val interaction =
            SwingShellSuggestionInteraction(
                request,
                handler,
                combinedFeedback,
            )
        currentFeedback = combinedFeedback
        feedbackForPublication = { sourceFeedback = it }
        interaction.sessionEditContext = expected
        interaction.contextIsCurrent = { session() === bound && eligible() && bound.isCommandEditCurrent(expected) }
        active = interaction
        isAutomatic = automatic
        selectedPopup = if (coexistence == SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION) popupCommand() else null
        interaction.addChangeListener {
            if (active === interaction) {
                if (interaction.isActive) {
                    render()
                } else {
                    active = null
                    isAutomatic = false
                    source = null
                    sourceJob?.cancel()
                    observation?.cancel()
                    sourceJob = null
                    observation = null
                    feedbackForPublication = null
                    currentFeedback = null
                    hide()
                }
            }
        }
        val watcher =
            scope.launch(start = CoroutineStart.LAZY) {
                bound.commandEditChanges.collect {
                    if (active === interaction) interaction.validateContext()
                }
            }
        observation = watcher
        watcher.start()
        return interaction.takeIf { active === it && it.isActive }
    }

    fun request(
        interaction: SwingShellSuggestionInteraction,
        provider: SwingInlineShellSuggestionProvider,
    ) {
        if (active !== interaction || !interaction.validateContext()) return
        source = provider
        collectSource(interaction, provider)
    }

    private fun collectSource(
        interaction: SwingShellSuggestionInteraction,
        provider: SwingInlineShellSuggestionProvider,
    ) {
        val revision = ++sourceRevision
        sourceJob?.cancel()
        // Each refreshed source captures feedback against its own publication. The original
        // interaction retains edit authority; a refresh cannot grant a newer editing capability.
        hide()
        interaction.publish(emptyList())
        if (active !== interaction || sourceRevision != revision) return
        val opened =
            try {
                provider.open(SwingInlineShellSuggestionRequest(interaction.request, selectedPopup))
            } catch (cancelled: CancellationException) {
                if (active === interaction && sourceRevision == revision) close()
                throw cancelled
            } catch (error: Exception) {
                fail(interaction, revision, error)
                return
            }
        if (active !== interaction || sourceRevision != revision || !interaction.validateContext()) return
        val collection =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    opened.suggestions.flowOn(sourceDispatcher).conflate().collect { suggestions ->
                        this@launch.ensureActive()
                        if (active === interaction && revision == sourceRevision && interaction.validateContext()) {
                            feedbackForPublication?.invoke(opened.feedbackHandler)
                            interaction.publish(suggestions, selectedIndex = if (suggestions.isEmpty()) -1 else 0)
                        }
                    }
                    if (active === interaction && revision == sourceRevision && interaction.snapshot.suggestions.isEmpty()) close()
                } catch (cancelled: CancellationException) {
                    if (active === interaction && revision == sourceRevision) close()
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    fail(interaction, revision, error)
                }
            }
        sourceJob = collection
        collection.start()
    }

    private fun fail(
        interaction: SwingShellSuggestionInteraction,
        revision: Long,
        error: Exception,
    ) {
        if (active !== interaction || sourceRevision != revision) return
        close(SwingShellSuggestionCloseReason.FAILED)
        failure(interaction, error)
    }

    fun popupChanged() {
        val next = if (coexistence == SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION) popupCommand() else null
        val changed = next != selectedPopup
        selectedPopup = next
        val interaction = active ?: return
        val provider = source
        if (changed && provider != null && interaction.validateContext()) collectSource(interaction, provider) else render()
    }

    /** A valid result may have arrived before the copied frame caught up to its edit context. */
    fun frameChanged() {
        if (!visible()) render()
    }

    private fun render() {
        val interaction = active ?: return
        if (!interaction.validateContext()) return
        if (popupOwnsInput()) {
            hide()
            return
        }
        val current = interaction.snapshot
        val suggestion = current.selectedSuggestion
        val result = suggestion?.commandTextAfterReplacement(interaction.request)
        val prefix = interaction.request.commandText
        val popup = selectedPopup
        if (result == null ||
            !result.startsWith(prefix) ||
            result.length == prefix.length ||
            coexistence == SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION &&
            popup != null &&
            !result.startsWith(popup)
        ) {
            hide()
            return
        }
        present(checkNotNull(interaction.sessionEditContext), result.substring(prefix.length))
    }

    fun accept(): SwingShellSuggestionAcceptanceResult {
        val interaction = active ?: return SwingShellSuggestionAcceptanceResult.UNAVAILABLE
        if (!visible()) return SwingShellSuggestionAcceptanceResult.UNAVAILABLE
        val publication = interaction.snapshot
        return interaction.tryAccept(publication, publication.selectedIndex)
    }

    fun acceptNextWord(): SwingShellSuggestionAcceptanceResult {
        val interaction = active ?: return SwingShellSuggestionAcceptanceResult.UNAVAILABLE
        if (!visible()) return SwingShellSuggestionAcceptanceResult.UNAVAILABLE
        val publication = interaction.snapshot
        val candidate = publication.selectedSuggestion ?: return SwingShellSuggestionAcceptanceResult.UNAVAILABLE
        val command =
            candidate.commandTextAfterReplacement(interaction.request)
                ?: return SwingShellSuggestionAcceptanceResult.INVALID_EDIT
        val prefix = interaction.request.commandText
        if (!command.startsWith(prefix) || command.length == prefix.length) return SwingShellSuggestionAcceptanceResult.INVALID_EDIT
        val end = nextInlineWordEnd(command, prefix.length)
        val acceptedCommand = command.substring(0, end)
        val edit =
            candidate.copy(
                replacementText = command.substring(prefix.length, end),
                replacementStartOffset = prefix.length,
                replacementEndOffset = prefix.length,
            )
        if (end == command.length) return interaction.tryAccept(publication, publication.selectedIndex, edit, partial = false)
        val expected = checkNotNull(interaction.sessionEditContext)
        val bound = session() ?: return SwingShellSuggestionAcceptanceResult.UNAVAILABLE
        val feedback = checkNotNull(currentFeedback)
        val revision = sourceRevision
        val result = interaction.tryAccept(publication, publication.selectedIndex, edit, partial = true)
        // Acceptance callbacks may replace either the session or the request.
        if (result == SwingShellSuggestionAcceptanceResult.ACCEPTED &&
            sourceRevision == revision &&
            active == null &&
            session() === bound &&
            eligible()
        ) {
            val waiting =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val next = bound.awaitCommandEditAfterInput(expected, acceptedCommand) ?: return@launch
                        if (sourceRevision != revision || session() !== bound || !eligible()) {
                            next.cancel()
                            return@launch
                        }
                        continuation = null
                        val resumed = begin(feedback, confirmed = next)
                        if (resumed == null) {
                            next.cancel()
                        } else {
                            resumed.publish(
                                listOf(
                                    candidate.copy(
                                        replacementText = command.substring(acceptedCommand.length),
                                        replacementStartOffset = acceptedCommand.length,
                                        replacementEndOffset = acceptedCommand.length,
                                    ),
                                ),
                                selectedIndex = 0,
                            )
                        }
                    } finally {
                        if (sourceRevision == revision) continuation = null
                    }
                }
            continuation = waiting
            waiting.start()
        }
        return result
    }

    fun dismiss(): Boolean {
        val interaction = active ?: return false
        return visible() && interaction.dismiss(interaction.snapshot)
    }

    fun actionFor(event: KeyEvent): SwingShellSuggestionAction? = if (active != null && visible()) keymap.actionFor(event) else null

    fun handleAction(
        action: SwingShellSuggestionAction,
        expected: SwingShellSuggestionSnapshot?,
    ): Boolean {
        if (expected == null || publication !== expected) return true
        return when (action) {
            SwingShellSuggestionAction.ACCEPT, SwingShellSuggestionAction.ACCEPT_SELECTED -> {
                accept()
                true
            }
            SwingShellSuggestionAction.DISMISS -> {
                dismiss()
                true
            }
            SwingShellSuggestionAction.ACCEPT_NEXT_WORD -> {
                acceptNextWord()
                true
            }
            else -> false
        }
    }

    fun invalidate(expected: TerminalCommandEditContext) {
        if (active?.sessionEditContext === expected) close(SwingShellSuggestionCloseReason.INVALIDATED)
    }

    fun close(reason: SwingShellSuggestionCloseReason = SwingShellSuggestionCloseReason.CANCELLED) {
        val previous = active
        active = null
        isAutomatic = false
        source = null
        feedbackForPublication = null
        currentFeedback = null
        sourceRevision++
        continuation?.cancel()
        continuation = null
        sourceJob?.cancel()
        sourceJob = null
        observation?.cancel()
        observation = null
        hide()
        previous?.close(reason)
    }
}
