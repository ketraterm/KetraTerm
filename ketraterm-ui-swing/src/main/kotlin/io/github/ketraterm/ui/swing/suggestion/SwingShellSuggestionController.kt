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
import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.event.KeyEvent

/** Embedded presentation adapter; the request interaction owns candidates, selection, admission, and feedback. */
internal class SwingShellSuggestionController(
    private val host: SwingShellSuggestionHost,
    viewFactory: SwingShellSuggestionViewFactory = SwingShellSuggestionViewFactory.DEFAULT,
) {
    private var interaction: SwingShellSuggestionInteraction? = null
    private var viewportStartIndex = 0
    private var anchorColumn = 0
    private var anchorRow = 0
    private var presentationRevision = 0L
    private var visualSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
    private val changeListener =
        SwingShellSuggestionInteractionListener { changed ->
            if (interaction === changed) updatePresentation()
        }
    private val view =
        viewFactory.create(
            object : SwingShellSuggestionViewListener {
                override fun onSuggestionHovered(
                    snapshot: SwingShellSuggestionViewSnapshot,
                    index: Int,
                ) {
                    val current = publication(snapshot) ?: return
                    if (index !in snapshot.visibleSuggestions.indices) return
                    interaction?.select(current, snapshot.viewportStartIndex + index)
                }

                override fun onSuggestionClicked(
                    snapshot: SwingShellSuggestionViewSnapshot,
                    index: Int,
                ) {
                    val current = publication(snapshot) ?: return
                    if (index !in snapshot.visibleSuggestions.indices) return
                    val active = interaction ?: return
                    val global = snapshot.viewportStartIndex + index
                    if (active.select(current, global)) accept(active, current, global)
                }

                override fun onSuggestionScrollRequested(
                    snapshot: SwingShellSuggestionViewSnapshot,
                    delta: Int,
                ) {
                    if (publication(snapshot) == null || delta == 0) return
                    val active = interaction ?: return
                    val current = active.snapshot
                    val bounded = delta.coerceIn(-3, 3)
                    val selected =
                        current.selectedIndex.takeIf { it >= 0 }
                            ?: if (bounded > 0) viewportStartIndex - 1 else viewportStartIndex
                    active.select(current, (selected + bounded).coerceIn(0, current.suggestions.lastIndex))
                }
            },
        )

    val popup get() = view.component

    fun present(
        interaction: SwingShellSuggestionInteraction,
        anchorColumn: Int = 0,
        anchorRow: Int = 0,
    ): Boolean {
        require(anchorColumn >= 0 && anchorRow >= 0) { "suggestion anchor must be nonnegative" }
        if (!host.settings.smartSuggestionsEnabled || !interaction.isActive) return false
        if (this.interaction !== interaction) {
            this.interaction?.removeChangeListener(changeListener)
            this.interaction = interaction
            viewportStartIndex = 0
            interaction.addChangeListener(changeListener)
        }
        this.anchorColumn = anchorColumn
        this.anchorRow = anchorRow
        updatePresentation()
        return this.interaction === interaction && interaction.isActive
    }

    fun hide(): Boolean {
        if (interaction == null && !view.component.isVisible && visualSnapshot.visibleSuggestions.isEmpty()) return false
        presentationRevision++
        interaction?.removeChangeListener(changeListener)
        interaction = null
        viewportStartIndex = 0
        val changed = view.component.isVisible || visualSnapshot.visibleSuggestions.isNotEmpty()
        visualSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
        cleanupSwingResources(
            { view.component.isVisible = false },
            { view.update(visualSnapshot) },
            host::revalidate,
            host::repaint,
        )
        return changed
    }

    fun close() = cleanupSwingResources({ hide() }, view::close)

    fun state(): SwingShellSuggestionState {
        val active = interaction ?: return SwingShellSuggestionState.EMPTY
        val current = active.snapshot
        if (!active.isActive || !view.component.isVisible || current.suggestions.isEmpty()) return SwingShellSuggestionState.EMPTY
        return SwingShellSuggestionState(
            true,
            current.suggestions.size,
            current.selectedIndex,
            anchorColumn,
            anchorRow,
            current.selectedSuggestion,
        )
    }

    fun handleKeyPressed(event: KeyEvent): Boolean {
        val active = interaction ?: return false
        if (!host.settings.smartSuggestionsEnabled ||
            !active.isActive ||
            !view.component.isVisible ||
            active.snapshot.suggestions.isEmpty()
        ) {
            return false
        }
        val action = host.suggestionKeymap.actionFor(event) ?: return false
        if (action == SwingShellSuggestionAction.ACCEPT_SELECTED &&
            event.keyCode == KeyEvent.VK_ENTER &&
            !host.settings.acceptSelectedSuggestionWithEnter
        ) {
            return false
        }
        val handled = handleAction(active, action)
        if (handled) event.consume()
        return handled
    }

    private fun handleAction(
        active: SwingShellSuggestionInteraction,
        action: SwingShellSuggestionAction,
    ): Boolean {
        val current = active.snapshot
        val items = current.suggestions
        val selected = current.selectedIndex
        if (action == SwingShellSuggestionAction.ACCEPT_SELECTED && selected !in items.indices) return false
        when (action) {
            SwingShellSuggestionAction.SELECT_FIRST -> active.select(current, 0)
            SwingShellSuggestionAction.SELECT_LAST -> active.select(current, items.lastIndex)
            SwingShellSuggestionAction.SELECT_NEXT ->
                active.select(
                    current,
                    if (selected !in items.indices ||
                        selected == items.lastIndex
                    ) {
                        0
                    } else {
                        selected + 1
                    },
                )
            SwingShellSuggestionAction.SELECT_PREVIOUS ->
                active.select(
                    current,
                    if (selected !in items.indices ||
                        selected == 0
                    ) {
                        items.lastIndex
                    } else {
                        selected - 1
                    },
                )
            SwingShellSuggestionAction.SELECT_NEXT_PAGE ->
                active.select(
                    current,
                    (
                        (if (selected >= 0) selected else -1).toLong() +
                            view.maximumVisibleSuggestions
                    ).coerceIn(0, items.lastIndex.toLong()).toInt(),
                )
            SwingShellSuggestionAction.SELECT_PREVIOUS_PAGE ->
                active.select(
                    current,
                    (
                        (if (selected >= 0) selected else items.size).toLong() -
                            view.maximumVisibleSuggestions
                    ).coerceIn(0, items.lastIndex.toLong()).toInt(),
                )
            SwingShellSuggestionAction.ACCEPT ->
                when {
                    selected in items.indices -> accept(active, current, selected)
                    items.size == 1 -> accept(active, current, 0)
                    else -> active.select(current, 0)
                }
            SwingShellSuggestionAction.ACCEPT_SELECTED -> accept(active, current, selected)
            SwingShellSuggestionAction.DISMISS -> {
                val revision = presentationRevision
                active.dismiss(current)
                if (interaction == null && presentationRevision == revision + 1) host.requestFocusInWindow()
            }
        }
        return true
    }

    private fun accept(
        active: SwingShellSuggestionInteraction,
        publication: SwingShellSuggestionSnapshot,
        index: Int,
    ): Boolean {
        if (!host.settings.smartSuggestionsEnabled) return false
        val revision = presentationRevision
        val result = active.tryAccept(publication, index)
        if (result == SwingShellSuggestionAcceptanceResult.ACCEPTED && interaction == null && presentationRevision == revision + 1) {
            host.requestFocusInWindow()
        }
        return true
    }

    private fun publication(snapshot: SwingShellSuggestionViewSnapshot): SwingShellSuggestionSnapshot? {
        if (snapshot !== visualSnapshot) return null
        val active = interaction ?: return null
        return active.snapshot.takeIf { active.isActive && snapshot.publication?.suggestions === it.suggestions }
    }

    private fun updatePresentation() {
        val active = interaction ?: return
        if (!active.isActive) {
            hide()
            return
        }
        val revision = ++presentationRevision
        val current = active.snapshot
        val items = current.suggestions
        val maximum = view.maximumVisibleSuggestions
        require(maximum > 0) { "maximumVisibleSuggestions must be positive" }
        if (items.isEmpty()) {
            visualSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
            cleanupSwingResources({ view.component.isVisible = false }, { view.update(visualSnapshot) }, host::revalidate, host::repaint)
            return
        }
        val selected = current.selectedIndex
        viewportStartIndex =
            when {
                items.size <= maximum -> 0
                selected < 0 -> viewportStartIndex.coerceIn(0, items.size - maximum)
                selected < viewportStartIndex -> selected
                selected >= viewportStartIndex + maximum -> selected - maximum + 1
                else -> viewportStartIndex
            }
        val end = minOf(items.size.toLong(), viewportStartIndex.toLong() + maximum).toInt()
        visualSnapshot = SwingShellSuggestionViewSnapshot.fromPublication(current, viewportStartIndex, end)
        view.component.isVisible = true
        if (presentationRevision != revision) return
        view.update(visualSnapshot)
        if (presentationRevision != revision) return
        host.revalidate()
        if (presentationRevision != revision) return
        host.repaint()
    }
}

internal interface SwingShellSuggestionHost {
    val settings: SwingSettings
    val suggestionKeymap: SwingShellSuggestionKeymap

    fun revalidate()

    fun repaint()

    fun requestFocusInWindow(): Boolean
}
