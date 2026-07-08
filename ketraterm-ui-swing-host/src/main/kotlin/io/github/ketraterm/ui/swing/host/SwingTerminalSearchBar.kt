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
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private val PANEL_SHADOW = Color(0x70000000, true)
private val PANEL_BACKGROUND = Color(0xF01F2227.toInt(), true)
private val PANEL_BORDER = Color(0x55414852, true)
private val FOREGROUND = Color(0xFFE8EAED.toInt(), true)
private val COUNTER_FOREGROUND = Color(0xFFA4ABB6.toInt(), true)
private val TEXT_FIELD_BACKGROUND = Color(0xFF25282E.toInt(), true)
private val TEXT_FIELD_BORDER = Color(0x4A4B5563, true)
private val TEXT_FIELD_FOCUS_BORDER = Color(0xFF3574F0.toInt(), true)
private val TEXT_FIELD_PLACEHOLDER = Color(0xFF8B929D.toInt(), true)
private val SEARCH_ICON_FOREGROUND = Color(0xFFB6BBC4.toInt(), true)
private val BUTTON_HOVER_BACKGROUND = Color(0x18FFFFFF, true)
private val BUTTON_PRESSED_BACKGROUND = Color(0x2CFFFFFF, true)
private val BUTTON_SELECTED_BACKGROUND = Color(0x553574F0, true)
private val BUTTON_SELECTED_FOREGROUND = Color(0xFFFFFFFF.toInt(), true)

private class SearchTextField(
    columns: Int,
) : JTextField(columns) {
    init {
        isOpaque = false
        caretColor = FOREGROUND
        foreground = FOREGROUND
        border = BorderFactory.createEmptyBorder(4, 34, 4, 48)
        margin = Insets(0, 0, 0, 0)
        addFocusListener(
            object : FocusListener {
                override fun focusGained(event: FocusEvent) = repaint()

                override fun focusLost(event: FocusEvent) = repaint()
            },
        )
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, SEARCH_FIELD_HEIGHT)
    }

    override fun getMinimumSize(): Dimension = Dimension(160, SEARCH_FIELD_HEIGHT)

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = TEXT_FIELD_BACKGROUND
            g.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
            g.color = if (isFocusOwner) TEXT_FIELD_FOCUS_BORDER else TEXT_FIELD_BORDER
            g.drawRoundRect(1, 1, width - 2, height - 2, 8, 8)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
        paintSearchAffordances(graphics)
    }

    private fun paintSearchAffordances(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintSearchIcon(g, 13, height / 2)
            if (text.isEmpty() && !isFocusOwner) {
                g.color = TEXT_FIELD_PLACEHOLDER
                g.font = font
                val metrics = g.fontMetrics
                val y = ((height - metrics.height) / 2) + metrics.ascent
                g.drawString("Search", 34, y)
            }
        } finally {
            g.dispose()
        }
    }

    private fun paintSearchIcon(
        graphics: Graphics2D,
        x: Int,
        centerY: Int,
    ) {
        val oldStroke = graphics.stroke
        try {
            graphics.color = SEARCH_ICON_FOREGROUND
            graphics.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            graphics.drawOval(x, centerY - 7, 11, 11)
            graphics.drawLine(x + 9, centerY + 3, x + 15, centerY + 9)
        } finally {
            graphics.stroke = oldStroke
        }
    }

    private companion object {
        private const val SEARCH_FIELD_HEIGHT = 30
    }
}

/**
 * Factory for text controls used by [SwingTerminalSearchBar].
 *
 * Hosts can provide IDE-specific text component subclasses while reusing the
 * shared host search behavior. Command buttons and toggles are intentionally
 * owned by the search bar so hover, pressed, and selected states stay
 * consistent across Swing hosts.
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

    companion object {
        /**
         * Default plain-Swing text control factory.
         */
        @JvmField
        val DEFAULT: SwingTerminalSearchBarComponentFactory =
            object : SwingTerminalSearchBarComponentFactory {
                override fun textField(columns: Int): JTextField = SearchTextField(columns)

                override fun label(text: String): JLabel = JLabel(text)
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
        private val queryField = componentFactory.textField(24)
        private val counterLabel = componentFactory.label("0/0")
        private val previousButton = IconButton(ButtonIcon.PREVIOUS)
        private val nextButton = IconButton(ButtonIcon.NEXT)
        private val caseSensitiveToggle = FlatToggleButton("Aa")
        private val searchInputPanel = SearchInputPanel(queryField, caseSensitiveToggle)
        private val closeButton = IconButton(ButtonIcon.CLOSE)
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
            previousButton.toolTipText = "Previous match"
            nextButton.toolTipText = "Next match"
            closeButton.toolTipText = "Close search"
            caseSensitiveToggle.toolTipText = "Match case"
            counterLabel.horizontalAlignment = SwingConstants.CENTER
            counterLabel.preferredSize = Dimension(COUNTER_LABEL_WIDTH, COMMAND_BUTTON_HEIGHT)
            counterLabel.minimumSize = counterLabel.preferredSize

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
            searchPanel.add(searchInputPanel, constraints(gridX++, weightX = 1.0, fill = GridBagConstraints.HORIZONTAL))
            searchPanel.add(counterLabel, constraints(gridX++))
            searchPanel.add(previousButton, constraints(gridX++))
            searchPanel.add(nextButton, constraints(gridX++, leftInset = 1))
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
            queryField.border = BorderFactory.createEmptyBorder(4, 34, 4, 48)
            counterLabel.foreground = COUNTER_FOREGROUND
            previousButton.foreground = FOREGROUND
            nextButton.foreground = FOREGROUND
            closeButton.foreground = FOREGROUND
            caseSensitiveToggle.foreground = FOREGROUND
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

        private class SearchInputPanel(
            private val queryField: JTextField,
            private val caseSensitiveToggle: JToggleButton,
        ) : JPanel(null) {
            init {
                isOpaque = false
                add(queryField)
                add(caseSensitiveToggle)
                setComponentZOrder(caseSensitiveToggle, 0)
                setComponentZOrder(queryField, 1)
            }

            override fun getPreferredSize(): Dimension = queryField.preferredSize

            override fun getMinimumSize(): Dimension = queryField.minimumSize

            override fun getMaximumSize(): Dimension = queryField.maximumSize

            override fun doLayout() {
                queryField.setBounds(0, 0, width, height)
                val buttonSize = caseSensitiveToggle.preferredSize
                val buttonX = width - buttonSize.width - 5
                val buttonY = (height - buttonSize.height) / 2
                caseSensitiveToggle.setBounds(buttonX, buttonY, buttonSize.width, buttonSize.height)
            }
        }

        private enum class ButtonIcon {
            PREVIOUS,
            NEXT,
            CLOSE,
        }

        private class IconButton(
            private val icon: ButtonIcon,
        ) : JButton() {
            init {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                isOpaque = false
                isFocusable = false
                isRolloverEnabled = true
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = Insets(0, 0, 0, 0)
                foreground = FOREGROUND
                addMouseListener(RepaintOnHoverListener)
            }

            override fun getPreferredSize(): Dimension = Dimension(ICON_BUTTON_WIDTH, COMMAND_BUTTON_HEIGHT)

            override fun getMinimumSize(): Dimension = preferredSize

            override fun getMaximumSize(): Dimension = preferredSize

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    paintFlatButtonBackground(g, width, height, model)
                    paintIcon(g, icon, width, height, foreground)
                } finally {
                    g.dispose()
                }
            }
        }

        private class FlatToggleButton(
            text: String,
        ) : JToggleButton(text) {
            init {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                isOpaque = false
                isFocusable = false
                isRolloverEnabled = true
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = Insets(0, 0, 0, 0)
                foreground = FOREGROUND
                font = font.deriveFont(Font.BOLD)
                addMouseListener(RepaintOnHoverListener)
            }

            override fun getPreferredSize(): Dimension = Dimension(TOGGLE_BUTTON_WIDTH, COMMAND_BUTTON_HEIGHT)

            override fun getMinimumSize(): Dimension = preferredSize

            override fun getMaximumSize(): Dimension = preferredSize

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    paintFlatButtonBackground(g, width, height, model, isSelected)
                    foreground = if (isSelected) BUTTON_SELECTED_FOREGROUND else FOREGROUND
                } finally {
                    g.dispose()
                }
                super.paintComponent(graphics)
            }
        }

        private class SearchPanel : JPanel(GridBagLayout()) {
            init {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            }

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = PANEL_SHADOW
                    g.fillRoundRect(2, 3, width - 4, height - 4, 14, 14)
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
            private const val COMMAND_BUTTON_HEIGHT = 28
            private const val ICON_BUTTON_WIDTH = 30
            private const val TOGGLE_BUTTON_WIDTH = 38
            private const val COUNTER_LABEL_WIDTH = 46

            private val RepaintOnHoverListener =
                object : MouseAdapter() {
                    override fun mouseEntered(event: MouseEvent) = event.component.repaint()

                    override fun mouseExited(event: MouseEvent) = event.component.repaint()
                }

            private fun paintFlatButtonBackground(
                graphics: Graphics2D,
                width: Int,
                height: Int,
                model: ButtonModel,
                isSelected: Boolean = false,
            ) {
                when {
                    isSelected -> graphics.color = BUTTON_SELECTED_BACKGROUND
                    model.isPressed -> graphics.color = BUTTON_PRESSED_BACKGROUND
                    model.isRollover -> graphics.color = BUTTON_HOVER_BACKGROUND
                    else -> return
                }
                graphics.fillRoundRect(0, 0, width, height, 6, 6)
            }

            private fun paintIcon(
                graphics: Graphics2D,
                icon: ButtonIcon,
                width: Int,
                height: Int,
                color: Color,
            ) {
                val oldStroke = graphics.stroke
                try {
                    graphics.color = color
                    graphics.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val centerX = width / 2
                    val centerY = height / 2
                    when (icon) {
                        ButtonIcon.PREVIOUS -> {
                            graphics.drawLine(centerX, centerY + 6, centerX, centerY - 5)
                            graphics.drawLine(centerX, centerY - 5, centerX - 4, centerY - 1)
                            graphics.drawLine(centerX, centerY - 5, centerX + 4, centerY - 1)
                        }
                        ButtonIcon.NEXT -> {
                            graphics.drawLine(centerX, centerY - 6, centerX, centerY + 5)
                            graphics.drawLine(centerX, centerY + 5, centerX - 4, centerY + 1)
                            graphics.drawLine(centerX, centerY + 5, centerX + 4, centerY + 1)
                        }
                        ButtonIcon.CLOSE -> {
                            graphics.drawLine(centerX - 4, centerY - 4, centerX + 4, centerY + 4)
                            graphics.drawLine(centerX + 4, centerY - 4, centerX - 4, centerY + 4)
                        }
                    }
                } finally {
                    graphics.stroke = oldStroke
                }
            }
        }
    }
