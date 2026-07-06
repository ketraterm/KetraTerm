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
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
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
        private val previousButton = componentFactory.button("<")
        private val nextButton = componentFactory.button(">")
        private val caseSensitiveToggle = componentFactory.checkBox("Aa")
        private val closeButton = componentFactory.button("x")

        /**
         * Swing component that hosts should add to their pane chrome.
         */
        val component: JComponent =
            JPanel(FlowLayout(FlowLayout.RIGHT, HORIZONTAL_GAP, VERTICAL_GAP)).apply {
                isVisible = false
                isOpaque = true
                border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                add(queryField)
                add(counterLabel)
                add(previousButton)
                add(nextButton)
                add(caseSensitiveToggle)
                add(closeButton)
            }

        init {
            queryField.toolTipText = "Search terminal output"
            counterLabel.toolTipText = "Active match and total matches"
            configureButton(previousButton, "Previous match")
            configureButton(nextButton, "Next match")
            configureButton(closeButton, "Close search")
            caseSensitiveToggle.toolTipText = "Match case"
            caseSensitiveToggle.margin = Insets(2, 6, 2, 6)
            caseSensitiveToggle.isFocusable = false
            caseSensitiveToggle.isOpaque = false

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
            component.revalidate()
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
            component.revalidate()
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
            val terminalBackground = terminal.background ?: Color.BLACK
            val terminalForeground = terminal.foreground ?: Color.WHITE
            val barBackground = blend(terminalBackground, terminalForeground, 0.08f)
            val fieldBackground = blend(terminalBackground, terminalForeground, 0.14f)
            val borderColor = blend(terminalBackground, terminalForeground, 0.22f)

            component.background = barBackground
            component.foreground = terminalForeground
            queryField.background = fieldBackground
            queryField.foreground = terminalForeground
            queryField.caretColor = terminalForeground
            queryField.border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(borderColor),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8),
                )
            counterLabel.foreground = blend(terminalForeground, terminalBackground, 0.28f)
            styleCommandColors(previousButton, terminalForeground, barBackground)
            styleCommandColors(nextButton, terminalForeground, barBackground)
            styleCommandColors(closeButton, terminalForeground, barBackground)
            caseSensitiveToggle.foreground = terminalForeground
            caseSensitiveToggle.background = barBackground
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

        private fun configureButton(
            button: JButton,
            tooltip: String,
        ) {
            button.toolTipText = tooltip
            button.margin = Insets(2, 7, 2, 7)
            button.isFocusable = false
            button.minimumSize = Dimension(28, 24)
            button.preferredSize = Dimension(28, 24)
        }

        private fun styleCommandColors(
            button: JButton,
            foreground: Color,
            background: Color,
        ) {
            button.foreground = foreground
            button.background = background
            button.isOpaque = false
            button.isContentAreaFilled = false
            button.isBorderPainted = true
            button.border = BorderFactory.createLineBorder(blend(background, foreground, 0.25f))
        }

        private companion object {
            private const val HORIZONTAL_GAP = 6
            private const val VERTICAL_GAP = 4

            private fun blend(
                first: Color,
                second: Color,
                secondWeight: Float,
            ): Color {
                val clampedWeight = secondWeight.coerceIn(0.0f, 1.0f)
                val firstWeight = 1.0f - clampedWeight
                return Color(
                    (first.red * firstWeight + second.red * clampedWeight).toInt().coerceIn(0, 255),
                    (first.green * firstWeight + second.green * clampedWeight).toInt().coerceIn(0, 255),
                    (first.blue * firstWeight + second.blue * clampedWeight).toInt().coerceIn(0, 255),
                )
            }
        }
    }
