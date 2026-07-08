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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Factory for Swing controls used by [SwingTerminalSearchBar].
 *
 * Hosts can provide IDE-specific component subclasses while reusing the shared
 * host search behavior.
 */
interface SwingTerminalSearchBarComponentFactory {
    /**
     * Creates the search query field.
     *
     * @param columns preferred text columns.
     * @return editable query field.
     */
    fun textField(columns: Int): JTextField

    /**
     * Creates a label for result counters.
     *
     * @param text initial label text.
     * @return label component.
     */
    fun label(text: String): JLabel

    /**
     * Creates a command button.
     *
     * @param text button text.
     * @return button component.
     */
    fun button(text: String): JButton

    /**
     * Creates a binary option toggle.
     *
     * @param text toggle text.
     * @return check box component.
     */
    fun checkBox(text: String): JCheckBox

    companion object {
        /**
         * Default plain-Swing control factory.
         */
        @JvmField
        val DEFAULT: SwingTerminalSearchBarComponentFactory =
            object : SwingTerminalSearchBarComponentFactory {
                override fun textField(columns: Int): JTextField = JTextField(columns)

                override fun label(text: String): JLabel = JLabel(text)

                override fun button(text: String): JButton = JButton(text)

                override fun checkBox(text: String): JCheckBox = JCheckBox(text)
            }
    }
}

/**
 * Optional host-owned search bar for a [SwingTerminal].
 *
 * The bar owns visible search chrome only. Search scanning, highlight
 * projection, and result navigation stay in [SwingTerminal]. Hosts decide
 * whether to instantiate this class, where to mount [component], and which
 * shortcut opens it.
 *
 * @param terminal terminal whose headless search API backs this bar.
 * @param componentFactory factory for host-specific Swing controls.
 */
class SwingTerminalSearchBar
    @JvmOverloads
    constructor(
        private val terminal: SwingTerminal,
        componentFactory: SwingTerminalSearchBarComponentFactory = SwingTerminalSearchBarComponentFactory.DEFAULT,
    ) {
        private var suppressDocumentEvents = false
        private val queryField = componentFactory.textField(28)
        private val counterLabel = componentFactory.label("0/0")
        private val previousButton = componentFactory.button("\u25B2")
        private val nextButton = componentFactory.button("\u25BC")
        private val caseSensitiveToggle = componentFactory.checkBox("Aa")
        private val closeButton = componentFactory.button("\u2715")
        private val searchPanel = SearchPanel()

        /**
         * Swing component that hosts should mount as floating pane chrome.
         */
        val component: JComponent =
            JPanel(BorderLayout()).apply {
                isVisible = false
                isOpaque = false
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
                add(searchPanel, BorderLayout.EAST)
            }

        init {
            queryField.toolTipText = "Search terminal output"
            counterLabel.toolTipText = "Active match and total matches"
            configureCommandButton(previousButton, "Previous match", horizontalMargin = 4)
            configureCommandButton(nextButton, "Next match", horizontalMargin = 4)
            configureCommandButton(closeButton, "Close search", horizontalMargin = 6)
            caseSensitiveToggle.toolTipText = "Match case"
            caseSensitiveToggle.isFocusable = false
            caseSensitiveToggle.isOpaque = false
            caseSensitiveToggle.isContentAreaFilled = false
            caseSensitiveToggle.isBorderPainted = false
            caseSensitiveToggle.margin = Insets(4, 6, 4, 6)

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

            var gridX = 0
            searchPanel.add(queryField, constraints(gridX++, weightX = 1.0, fill = GridBagConstraints.HORIZONTAL))
            searchPanel.add(counterLabel, constraints(gridX++))
            searchPanel.add(previousButton, constraints(gridX++))
            searchPanel.add(nextButton, constraints(gridX++, leftInset = 1))
            searchPanel.add(caseSensitiveToggle, constraints(gridX++))
            searchPanel.add(closeButton, constraints(gridX))

            refreshColors()
        }

        /**
         * Opens the search bar and focuses the query field.
         */
        fun open() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { open() }
                return
            }
            refreshColors()
            component.isVisible = true
            setQueryText(terminal.currentSearchState().query)
            refreshCounter()
            revalidateHost()
            queryField.requestFocusInWindow()
            queryField.selectAll()
        }

        /**
         * Closes the search bar and clears active terminal search highlights.
         */
        fun close() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { close() }
                return
            }
            component.isVisible = false
            setQueryText("")
            terminal.clearSearch()
            refreshCounter()
            revalidateHost()
            terminal.requestFocusInWindow()
        }

        /**
         * Returns whether the search bar is visible.
         *
         * @return `true` when the bar is open.
         */
        fun isOpen(): Boolean = component.isVisible

        /**
         * Refreshes host colors from the terminal component.
         */
        fun refreshColors() {
            component.background = Color(0, 0, 0, 0)
            component.foreground = FOREGROUND
            searchPanel.background = PANEL_BACKGROUND
            searchPanel.foreground = FOREGROUND
            queryField.isOpaque = false
            queryField.foreground = FOREGROUND
            queryField.caretColor = FOREGROUND
            queryField.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            counterLabel.foreground = COUNTER_FOREGROUND
            styleCommandColors(previousButton)
            styleCommandColors(nextButton)
            styleCommandColors(closeButton)
            caseSensitiveToggle.foreground = FOREGROUND
            caseSensitiveToggle.background = PANEL_BACKGROUND
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

        private fun revalidateHost() {
            val parent = component.parent
            if (parent == null) {
                component.revalidate()
                component.repaint()
                return
            }
            parent.revalidate()
            parent.repaint()
        }

        private fun configureCommandButton(
            button: JButton,
            tooltip: String,
            horizontalMargin: Int,
        ) {
            button.toolTipText = tooltip
            button.margin = Insets(4, horizontalMargin, 4, horizontalMargin)
            button.isFocusable = false
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = false
        }

        private fun styleCommandColors(button: JButton) {
            button.foreground = FOREGROUND
            button.background = PANEL_BACKGROUND
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = false
        }

        private fun constraints(
            gridX: Int,
            weightX: Double = 0.0,
            fill: Int = GridBagConstraints.NONE,
            leftInset: Int = 6,
        ): GridBagConstraints =
            GridBagConstraints().apply {
                this.gridx = gridX
                this.gridy = 0
                this.weightx = weightX
                this.fill = fill
                this.insets = Insets(0, if (gridX == 0) 0 else leftInset, 0, 0)
                this.anchor = GridBagConstraints.CENTER
            }

        private class SearchPanel : JPanel(GridBagLayout()) {
            init {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            }

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = PANEL_BACKGROUND
                    g.fillRoundRect(1, 1, width - 2, height - 2, 12, 12)
                    g.color = PANEL_BORDER
                    g.drawRoundRect(1, 1, width - 2, height - 2, 12, 12)
                } finally {
                    g.dispose()
                }
                super.paintComponent(graphics)
            }
        }

        private companion object {
            private val PANEL_BACKGROUND = Color(0xEE202124.toInt(), true)
            private val PANEL_BORDER = Color(0x663C4043, true)
            private val FOREGROUND = Color(0xFFE8EAED.toInt(), true)
            private val COUNTER_FOREGROUND = Color(0xFF9AA0A6.toInt(), true)
        }
    }
