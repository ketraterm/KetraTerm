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
package io.github.ketraterm.intellij.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * IntelliJ-hosted search chrome for a terminal pane.
 */
internal class KetraTermTerminalSearchController(
    private val terminal: SwingTerminal,
    private val container: JPanel,
) {
    private var suppressDocumentEvents = false
    private val queryField = JBTextField(28)
    private val counterLabel = JBLabel("0/0")
    private val previousButton = JButton("Prev")
    private val nextButton = JButton("Next")
    private val caseSensitiveToggle = JCheckBox("Aa")
    private val closeButton = JButton("x")

    private val searchBar =
        JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
            isVisible = false
            isOpaque = true
            add(queryField)
            add(counterLabel)
            add(previousButton)
            add(nextButton)
            add(caseSensitiveToggle)
            add(closeButton)
        }

    init {
        queryField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = queryChanged()

                override fun removeUpdate(event: DocumentEvent) = queryChanged()

                override fun changedUpdate(event: DocumentEvent) = queryChanged()
            },
        )
        queryField.registerKeyboardAction(
            {
                terminal.selectNextSearchResult()
                refreshCounter()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
        queryField.registerKeyboardAction(
            {
                terminal.selectPreviousSearchResult()
                refreshCounter()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            JComponent.WHEN_FOCUSED,
        )
        queryField.registerKeyboardAction(
            { close() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED,
        )
        nextButton.addActionListener {
            terminal.selectNextSearchResult()
            refreshCounter()
        }
        previousButton.addActionListener {
            terminal.selectPreviousSearchResult()
            refreshCounter()
        }
        caseSensitiveToggle.addActionListener {
            terminal.setSearchCaseSensitive(caseSensitiveToggle.isSelected)
            refreshCounter()
        }
        closeButton.addActionListener { close() }

        searchBar.background = terminal.background
        container.add(searchBar, BorderLayout.NORTH)
    }

    fun open() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { open() }
            return
        }
        searchBar.background = terminal.background
        searchBar.isVisible = true
        setQueryText(terminal.currentSearchState().query)
        refreshCounter()
        container.revalidate()
        queryField.requestFocusInWindow()
        queryField.selectAll()
    }

    fun close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { close() }
            return
        }
        searchBar.isVisible = false
        setQueryText("")
        terminal.clearSearch()
        refreshCounter()
        container.revalidate()
        terminal.requestFocusInWindow()
    }

    private fun queryChanged() {
        if (suppressDocumentEvents) return
        terminal.search(queryField.text)
        refreshCounter()
    }

    private fun setQueryText(query: String) {
        if (queryField.text == query) return
        suppressDocumentEvents = true
        try {
            queryField.text = query
        } finally {
            suppressDocumentEvents = false
        }
    }

    private fun refreshCounter() {
        val state = terminal.currentSearchState()
        counterLabel.text =
            if (state.resultCount == 0) {
                "0/0"
            } else {
                "${state.activeResultIndex + 1}/${state.resultCount}"
            }
    }
}
