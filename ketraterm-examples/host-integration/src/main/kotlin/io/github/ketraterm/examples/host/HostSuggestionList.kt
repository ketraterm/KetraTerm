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
@file:OptIn(ExperimentalShellSuggestionsApi::class)

package io.github.ketraterm.examples.host

import io.github.ketraterm.ui.swing.suggestion.*
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.*

/** Supplies host visuals and pointer events. KetraTerm owns selection, keys and guarded editing. */
internal class HostSuggestionList(
    private val listener: SwingShellSuggestionViewListener,
) : SwingShellSuggestionView {
    private val model = DefaultListModel<SwingShellSuggestion>()
    private val list = JList(model)
    private var snapshot = SwingShellSuggestionViewSnapshot.EMPTY
    private var applyingSnapshot = false
    private val snapshotSelection =
        object : DefaultListSelectionModel() {
            override fun setSelectionInterval(
                index0: Int,
                index1: Int,
            ) {
                if (applyingSnapshot) super.setSelectionInterval(index0, index1)
            }

            override fun addSelectionInterval(
                index0: Int,
                index1: Int,
            ) {
                if (applyingSnapshot) super.addSelectionInterval(index0, index1)
            }

            override fun removeSelectionInterval(
                index0: Int,
                index1: Int,
            ) {
                if (applyingSnapshot) super.removeSelectionInterval(index0, index1)
            }

            override fun clearSelection() {
                if (applyingSnapshot) super.clearSelection()
            }

            override fun setLeadSelectionIndex(index: Int) {
                if (applyingSnapshot) super.setLeadSelectionIndex(index)
            }
        }
    private val pointer =
        object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                indexAt(event)?.let { listener.onSuggestionHovered(snapshot, it) }
            }

            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) indexAt(event)?.let { listener.onSuggestionClicked(snapshot, it) }
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                listener.onSuggestionScrollRequested(snapshot, event.wheelRotation)
                event.consume()
            }
        }
    override val component = JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)

    init {
        list.isFocusable = false
        list.selectionModel = snapshotSelection
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.accessibleContext.accessibleName = "Shell suggestions"
        val renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
        list.cellRenderer =
            ListCellRenderer { owner, value, index, selected, focus ->
                renderer.getListCellRendererComponent(owner, value.displayText, index, selected, focus)
            }
        list.addMouseListener(pointer)
        list.addMouseMotionListener(pointer)
        list.addMouseWheelListener(pointer)
        component.isWheelScrollingEnabled = false
    }

    override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
        check(SwingUtilities.isEventDispatchThread())
        this.snapshot = snapshot
        applyingSnapshot = true
        try {
            model.clear()
            snapshot.visibleSuggestions.forEach(model::addElement)
            list.selectedIndex = snapshot.selectedIndex
        } finally {
            applyingSnapshot = false
        }
        list.visibleRowCount = model.size.coerceIn(1, maximumVisibleSuggestions)
        component.preferredSize = Dimension(360, list.preferredScrollableViewportSize.height + 4)
    }

    private fun indexAt(event: MouseEvent): Int? {
        val index = list.locationToIndex(event.point)
        return index.takeIf { it >= 0 && list.getCellBounds(it, it).contains(event.point) }
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        list.removeMouseListener(pointer)
        list.removeMouseMotionListener(pointer)
        list.removeMouseWheelListener(pointer)
        update(SwingShellSuggestionViewSnapshot.EMPTY)
    }
}
