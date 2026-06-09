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
package com.gagik.terminal.standalone.ui

import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import com.gagik.terminal.ui.swing.api.TerminalViewportListener
import java.awt.event.AdjustmentEvent
import javax.swing.JScrollBar

/**
 * Bridges terminal-native scrollback coordinates to a top-origin Swing
 * scrollbar.
 */
internal class TerminalScrollbarAdapter(
    private val scrollbar: JScrollBar,
) : TerminalViewportListener {
    private var terminal: TerminalSwingTerminal? = null
    private var updatingFromTerminal: Boolean = false
    private var historySize: Int = 0

    init {
        scrollbar.addAdjustmentListener { event ->
            handleAdjustment(event)
        }
    }

    fun attach(terminal: TerminalSwingTerminal) {
        this.terminal = terminal
    }

    override fun viewportChanged(
        historySize: Int,
        scrollbackOffset: Double,
        renderOffset: Int,
        visibleRows: Int,
        requestedRows: Int,
    ) {
        this.historySize = historySize
        val safeVisibleRows = maxOf(1, visibleRows)
        val value = (historySize - scrollbackOffset.toInt()).coerceIn(0, historySize)
        val maximum = historySize + safeVisibleRows

        updatingFromTerminal = true
        try {
            scrollbar.isVisible = historySize > 0
            scrollbar.visibleAmount = safeVisibleRows
            scrollbar.maximum = maximum
            scrollbar.value = value
            scrollbar.blockIncrement = safeVisibleRows
        } finally {
            updatingFromTerminal = false
        }
    }

    private fun handleAdjustment(event: AdjustmentEvent) {
        if (updatingFromTerminal || event.valueIsAdjusting) return
        val targetOffset = (historySize - event.value).coerceIn(0, historySize)
        terminal?.scrollToScrollbackOffset(targetOffset)
    }
}
