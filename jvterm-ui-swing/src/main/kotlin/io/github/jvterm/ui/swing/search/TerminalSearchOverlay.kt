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
package io.github.jvterm.ui.swing.search

import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

interface SearchOverlayListener {
    fun onQueryChanged(query: String)

    fun onFindNext()

    fun onFindPrevious()

    fun onCloseSearch()

    fun onCaseSensitivityChanged(ignoreCase: Boolean)
}

class TerminalSearchOverlay(
    private val listener: SearchOverlayListener,
) : JPanel(GridBagLayout()) {
    private var suppressDocumentEvents = false
    private val queryField = SearchTextField(22)
    private val counterLabel = JLabel("0/0")
    private val previousButton = FlatButton("\u25B2", horizontalMargin = 4) // ▲
    private val nextButton = FlatButton("\u25BC", horizontalMargin = 4) // ▼
    private val caseToggle = FlatToggleButton("Aa")
    private val closeButton = FlatButton("\u2715", horizontalMargin = 6) // ✕

    private inner class SearchTextField(
        columns: Int,
    ) : JTextField(columns) {
        init {
            isOpaque = false
            caretColor = Color(0xFFE8EAED.toInt(), true)
            foreground = Color(0xFFE8EAED.toInt(), true)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            addFocusListener(
                object : FocusListener {
                    override fun focusGained(e: FocusEvent) = repaint()

                    override fun focusLost(e: FocusEvent) = repaint()
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0x26FFFFFF, true)
            g.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
            if (isFocusOwner) {
                g.color = Color(0x664285F4, true)
                g.drawRoundRect(1, 1, width - 2, height - 2, 8, 8)
            } else {
                g.color = Color(0x13FFFFFF, true)
                g.drawRoundRect(1, 1, width - 2, height - 2, 8, 8)
            }
            g.dispose()
            super.paintComponent(graphics)
        }
    }

    private fun paintFlatButtonBackground(
        g: Graphics2D,
        width: Int,
        height: Int,
        model: ButtonModel,
        isSelected: Boolean = false,
    ) {
        if (isSelected) {
            g.color = Color(0x404285F4, true)
            g.fillRoundRect(0, 0, width, height, 6, 6)
        } else if (model.isPressed) {
            g.color = Color(0x33FFFFFF, true)
            g.fillRoundRect(0, 0, width, height, 6, 6)
        } else if (model.isRollover) {
            g.color = Color(0x1AFFFFFF, true)
            g.fillRoundRect(0, 0, width, height, 6, 6)
        }
    }

    private open inner class FlatButton(
        text: String,
        horizontalMargin: Int = 8,
    ) : JButton(text) {
        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(4, horizontalMargin, 4, horizontalMargin)
            foreground = Color(0xFFE8EAED.toInt(), true)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) = repaint()

                    override fun mouseExited(e: MouseEvent) = repaint()
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintFlatButtonBackground(g, width, height, getModel())
            g.dispose()
            super.paintComponent(graphics)
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
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = Insets(4, 6, 4, 6)
            foreground = Color(0xFFE8EAED.toInt(), true)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) = repaint()

                    override fun mouseExited(e: MouseEvent) = repaint()
                },
            )
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintFlatButtonBackground(g, width, height, getModel(), isSelected)
            g.dispose()
            super.paintComponent(graphics)
        }
    }

    init {
        isOpaque = false
        background = Color(0xEE202124.toInt(), true)
        foreground = Color(0xFFE8EAED.toInt(), true)
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)

        counterLabel.foreground = Color(0xFF9AA0A6.toInt(), true)

        queryField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = queryChanged()

                override fun removeUpdate(event: DocumentEvent) = queryChanged()

                override fun changedUpdate(event: DocumentEvent) = queryChanged()
            },
        )

        queryField.registerKeyboardAction(
            { listener.onFindNext() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            WHEN_FOCUSED,
        )
        queryField.registerKeyboardAction(
            { listener.onFindPrevious() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            WHEN_FOCUSED,
        )
        queryField.registerKeyboardAction(
            { listener.onCloseSearch() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            WHEN_FOCUSED,
        )

        previousButton.addActionListener { listener.onFindPrevious() }
        nextButton.addActionListener { listener.onFindNext() }
        closeButton.addActionListener { listener.onCloseSearch() }
        caseToggle.addActionListener {
            listener.onCaseSensitivityChanged(!caseToggle.isSelected)
            listener.onQueryChanged(queryField.text)
        }

        var gridX = 0
        add(queryField, constraints(gridX++, weightX = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(counterLabel, constraints(gridX++))
        add(previousButton, constraints(gridX++))
        add(nextButton, constraints(gridX++, leftInset = 1))
        add(caseToggle, constraints(gridX++))
        add(closeButton, constraints(gridX))
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = background
        g.fillRoundRect(1, 1, width - 2, height - 2, 12, 12)
        g.color = Color(0x663C4043, true)
        g.drawRoundRect(1, 1, width - 2, height - 2, 12, 12)
        g.dispose()
        super.paintComponent(graphics)
    }

    fun setQueryText(query: String) {
        if (queryField.text == query) return
        suppressDocumentEvents = true
        try {
            queryField.text = query
        } finally {
            suppressDocumentEvents = false
        }
    }

    fun updateResultCounter(
        resultCount: Int,
        activeResultIndex: Int,
    ) {
        counterLabel.text =
            if (resultCount == 0) {
                "0/0"
            } else {
                "${activeResultIndex + 1}/$resultCount"
            }
    }

    fun focusQuery() {
        queryField.requestFocusInWindow()
        queryField.selectAll()
    }

    private fun queryChanged() {
        if (suppressDocumentEvents) return
        listener.onQueryChanged(queryField.text)
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
}
