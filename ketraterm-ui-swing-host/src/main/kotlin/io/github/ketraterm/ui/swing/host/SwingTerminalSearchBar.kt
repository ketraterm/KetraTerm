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
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.coroutines.CoroutineContext

/**
 * Optional host-owned search bar for a [SwingTerminal].
 *
 * The bar owns visible search chrome only. Search scanning, highlight
 * projection, and result navigation stay in [SwingTerminal]. Hosts decide
 * whether to instantiate this class, where to mount [component], and which
 * shortcut opens it.
 *
 * @param terminal terminal whose headless search API backs this bar.
 * @param messages host message bundle, read on the EDT outside painting. Static labels are captured at construction.
 */
public class SwingTerminalSearchBar
    public constructor(
        private val terminal: SwingTerminal,
        private val messages: SwingHostMessages,
    ) {
        /** Creates a search bar using the JVM display locale and bundled messages. */
        public constructor(terminal: SwingTerminal) : this(terminal, SwingHostMessages.forLocale())

        private var colors = SwingTerminalSearchColors()
        private val placeholder = messages.message("search.placeholder")
        private val searchingText = messages.message("search.searching")
        private val failedText = messages.message("search.failed")

        private var suppressDocumentEvents = false
        private val queryField = SearchTextField(24)
        private val counterLabel = JLabel(messages.message("search.matchCounter", 0, 0))
        private val previousButton = IconButton(ButtonIcon.PREVIOUS)
        private val nextButton = IconButton(ButtonIcon.NEXT)
        private val caseSensitiveToggle = FlatToggleButton(messages.message("search.matchCaseLabel"))
        private val searchInputPanel = SearchInputPanel(queryField, caseSensitiveToggle)
        private val closeButton = IconButton(ButtonIcon.CLOSE)
        private val searchPanel = SearchPanel()
        private var searchObservation: Job? = null
        private val searchScope =
            CoroutineScope(
                object : CoroutineDispatcher() {
                    override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()

                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) = SwingUtilities.invokeLater(block)
                },
            )

        /**
         * Swing component that hosts should mount as floating pane chrome.
         */
        public val component: JComponent =
            JPanel(BorderLayout()).apply {
                isVisible = false
                isOpaque = false
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
                add(searchPanel, BorderLayout.EAST)
            }

        init {
            component.addHierarchyListener {
                if (it.changeFlags and
                    HierarchyEvent.DISPLAYABILITY_CHANGED
                        .toLong() != 0L &&
                    !component.isDisplayable
                ) {
                    searchObservation?.cancel()
                    searchObservation = null
                }
            }
            queryField.toolTipText = messages.message("search.queryToolTip")
            counterLabel.toolTipText = messages.message("search.counterToolTip")
            previousButton.toolTipText = messages.message("search.previousToolTip")
            nextButton.toolTipText = messages.message("search.nextToolTip")
            closeButton.toolTipText = messages.message("search.closeToolTip")
            caseSensitiveToggle.toolTipText = messages.message("search.matchCaseToolTip")
            for (control in arrayOf<JComponent>(queryField, counterLabel, previousButton, nextButton, closeButton, caseSensitiveToggle)) {
                control.accessibleContext.accessibleName = control.toolTipText
            }
            counterLabel.horizontalAlignment = SwingConstants.CENTER
            counterLabel.preferredSize =
                Dimension(
                    maxOf(
                        COUNTER_LABEL_WIDTH,
                        counterLabel.getFontMetrics(counterLabel.font).stringWidth(searchingText) + 8,
                        counterLabel.getFontMetrics(counterLabel.font).stringWidth(failedText) + 8,
                        counterLabel.getFontMetrics(counterLabel.font).stringWidth(counterLabel.text) + 8,
                    ),
                    COMMAND_BUTTON_HEIGHT,
                )
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
        public fun open() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { open() }
                return
            }
            refreshColors()
            component.isVisible = true
            setQueryText(terminal.currentSearchState().query)
            if (searchObservation == null) {
                searchObservation =
                    searchScope.launch {
                        terminal.searchState.collect { refreshCounter() }
                    }
            }
            refreshCounter()
            revalidateHost()
            queryField.requestFocusInWindow()
            queryField.selectAll()
        }

        /**
         * Closes the search bar and clears active terminal search highlights.
         */
        public fun close() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { close() }
                return
            }
            component.isVisible = false
            searchObservation?.cancel()
            searchObservation = null
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
        public fun isOpen(): Boolean = component.isVisible

        /**
         * Reapplies the last supplied colors. Off-EDT calls enqueue work on the EDT.
         */
        public fun refreshColors() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { refreshColors() }
                return
            }
            refreshColors(colors)
        }

        /**
         * Applies one prepared host palette without changing the query, visibility or search.
         * Off-EDT calls enqueue work on the EDT; EDT calls apply immediately and repaint.
         * The snapshot is retained until replaced, including across close/open cycles.
         */
        public fun refreshColors(colors: SwingTerminalSearchColors) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater { refreshColors(colors) }
                return
            }
            this.colors = colors
            component.background = Color(0, 0, 0, 0)
            component.foreground = colors.foreground
            searchPanel.background = colors.panelBackground
            searchPanel.foreground = colors.foreground
            queryField.isOpaque = false
            queryField.foreground = colors.foreground
            queryField.caretColor = colors.foreground
            queryField.border = BorderFactory.createEmptyBorder(4, 34, 4, caseSensitiveToggle.preferredSize.width + 10)
            counterLabel.foreground = colors.counterForeground
            previousButton.foreground = colors.foreground
            nextButton.foreground = colors.foreground
            closeButton.foreground = colors.foreground
            caseSensitiveToggle.foreground = if (caseSensitiveToggle.isSelected) colors.buttonSelectedForeground else colors.foreground
            component.repaint()
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
                when {
                    state.failure != null -> failedText
                    state.isSearching -> searchingText
                    else ->
                        messages.message(
                            "search.matchCounter",
                            if (state.resultCount ==
                                0
                            ) {
                                0
                            } else {
                                state.activeResultIndex + 1
                            },
                            state.resultCount,
                        )
                }
            counterLabel.accessibleContext.accessibleDescription = counterLabel.text
            val width =
                maxOf(
                    counterLabel.minimumSize.width,
                    counterLabel.getFontMetrics(counterLabel.font).stringWidth(counterLabel.text) + 8,
                )
            if (width != counterLabel.preferredSize.width) {
                counterLabel.preferredSize = Dimension(width, COMMAND_BUTTON_HEIGHT)
                revalidateHost()
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

        private inner class IconButton(
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
                foreground = colors.foreground
                addMouseListener(RepaintOnHoverListener)
            }

            override fun getPreferredSize(): Dimension = Dimension(ICON_BUTTON_WIDTH, COMMAND_BUTTON_HEIGHT)

            override fun getMinimumSize(): Dimension = preferredSize

            override fun getMaximumSize(): Dimension = preferredSize

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    paintFlatButtonBackground(g, width, height, model, colors)
                    paintIcon(g, icon, width, height, foreground)
                } finally {
                    g.dispose()
                }
            }
        }

        private inner class FlatToggleButton(
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
                foreground = colors.foreground
                font = font.deriveFont(Font.BOLD)
                addMouseListener(RepaintOnHoverListener)
            }

            override fun getPreferredSize(): Dimension =
                Dimension(maxOf(TOGGLE_BUTTON_WIDTH, getFontMetrics(font).stringWidth(text) + 12), COMMAND_BUTTON_HEIGHT)

            override fun getMinimumSize(): Dimension = preferredSize

            override fun getMaximumSize(): Dimension = preferredSize

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    paintFlatButtonBackground(g, width, height, model, colors, isSelected)
                    foreground = if (isSelected) colors.buttonSelectedForeground else colors.foreground
                } finally {
                    g.dispose()
                }
                super.paintComponent(graphics)
            }
        }

        private inner class SearchPanel : JPanel(GridBagLayout()) {
            init {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            }

            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = colors.panelShadow
                    g.fillRoundRect(2, 3, width - 4, height - 4, 14, 14)
                    g.color = colors.panelBackground
                    g.fillRoundRect(1, 1, width - 2, height - 2, 12, 12)
                    g.color = colors.panelBorder
                    g.drawRoundRect(1, 1, width - 2, height - 2, 12, 12)
                } finally {
                    g.dispose()
                }
                super.paintComponent(graphics)
            }
        }

        private inner class SearchTextField(
            columns: Int,
        ) : JTextField(columns) {
            init {
                isOpaque = false
                caretColor = colors.foreground
                foreground = colors.foreground
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
                    g.color = colors.textFieldBackground
                    g.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
                    g.color = if (isFocusOwner) colors.textFieldFocusBorder else colors.textFieldBorder
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
                        g.color = colors.textFieldPlaceholder
                        g.font = font
                        val metrics = g.fontMetrics
                        val y = ((height - metrics.height) / 2) + metrics.ascent
                        g.drawString(placeholder, 34, y)
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
                    graphics.color = colors.searchIconForeground
                    graphics.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    graphics.drawOval(x, centerY - 7, 11, 11)
                    graphics.drawLine(x + 9, centerY + 3, x + 15, centerY + 9)
                } finally {
                    graphics.stroke = oldStroke
                }
            }
        }

        private companion object {
            private const val SEARCH_FIELD_HEIGHT = 30
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
                colors: SwingTerminalSearchColors,
                isSelected: Boolean = false,
            ) {
                when {
                    isSelected -> graphics.color = colors.buttonSelectedBackground
                    model.isPressed -> graphics.color = colors.buttonPressedBackground
                    model.isRollover -> graphics.color = colors.buttonHoverBackground
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
