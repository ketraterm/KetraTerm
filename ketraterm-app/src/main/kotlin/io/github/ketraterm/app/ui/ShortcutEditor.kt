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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.appMessages
import io.github.ketraterm.app.config.SuggestionKeyBindings
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.event.*
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Press-to-record shortcuts. Values remain typed; labels are presentation only. */
internal class ShortcutEditor(
    initial: Set<KeyStroke>,
    private val actionLabel: String,
) : JPanel(FlowLayout(FlowLayout.LEADING, 6, 4)) {
    var shortcuts: Set<KeyStroke> = initial.toSet()
        set(value) {
            require(value.all(SuggestionKeyBindings::isValidShortcut))
            val copied = value.toSet()
            if (field == copied) return
            val previous = field
            field = copied
            rebuild()
            firePropertyChange("shortcuts", previous, copied)
        }

    private var recording = false
    private val addButton =
        JButton("+").apply {
            toolTipText = appMessages.text("settings.shortcuts.add")
            accessibleContext.accessibleName = "$actionLabel: ${appMessages.text("settings.shortcuts.add")}"
            margin = Insets(3, 8, 3, 8)
            addActionListener { beginCapture() }
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(event: FocusEvent) = endCapture()
                },
            )
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(event: KeyEvent) {
                        if (!recording) return
                        event.consume()
                        when (event.keyCode) {
                            KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META,
                            KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_UNDEFINED,
                            -> return
                        }
                        val shortcut = KeyStroke.getKeyStrokeForEvent(event)
                        endCapture()
                        shortcuts = shortcuts + shortcut
                    }
                },
            )
        }
    private val cancelButton =
        JButton(appMessages.text("button.cancel")).apply {
            addActionListener { endCapture() }
        }

    init {
        require(shortcuts.all(SuggestionKeyBindings::isValidShortcut))
        isOpaque = false
        getAccessibleContext().accessibleName = actionLabel
        rebuild()
    }

    private fun beginCapture() {
        if (!isEnabled) return
        recording = true
        addButton.text = appMessages.text("settings.shortcuts.press")
        addButton.focusTraversalKeysEnabled = false
        cancelButton.isVisible = true
        revalidate()
        repaint()
        addButton.requestFocusInWindow()
    }

    private fun endCapture() {
        if (!recording) return
        recording = false
        addButton.text = "+"
        addButton.focusTraversalKeysEnabled = true
        cancelButton.isVisible = false
        revalidate()
        repaint()
    }

    private fun rebuild() {
        removeAll()
        if (shortcuts.isEmpty()) add(JLabel(appMessages.text("settings.shortcuts.empty")).apply { foreground = Chrome.textSecondary })
        for (shortcut in shortcuts) {
            val label = displayShortcut(shortcut)
            add(
                JButton("$label ×").apply {
                    margin = Insets(3, 8, 3, 8)
                    toolTipText = appMessages.text("settings.shortcuts.remove", label)
                    accessibleContext.accessibleName = "$actionLabel: ${appMessages.text("settings.shortcuts.remove", label)}"
                    addActionListener { shortcuts = shortcuts - shortcut }
                    isEnabled = this@ShortcutEditor.isEnabled
                },
            )
        }
        addButton.isEnabled = isEnabled
        cancelButton.isVisible = recording
        add(addButton)
        add(cancelButton)
        revalidate()
        repaint()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) endCapture()
        components.forEach { it.isEnabled = enabled }
    }

    override fun getPreferredSize(): Dimension {
        val flow = layout as FlowLayout
        val padding = insets
        val available = if (width > 0) width - padding.left - padding.right - 2 * flow.hgap else Int.MAX_VALUE
        var rowWidth = 0
        var rowHeight = 0
        var height = padding.top + padding.bottom + 2 * flow.vgap
        for (component in components) {
            if (!component.isVisible) continue
            val size = component.preferredSize
            val gap = if (rowWidth == 0) 0 else flow.hgap
            if (rowWidth > 0 && rowWidth + gap + size.width > available) {
                height += rowHeight + flow.vgap
                rowWidth = 0
                rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += flow.hgap
            rowWidth += size.width
            rowHeight = maxOf(rowHeight, size.height)
        }
        // Let the form allocate width instead of widening the whole page for a long shortcut list.
        return Dimension(0, height + rowHeight)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun setBounds(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val resized = width != this.width
        super.setBounds(x, y, width, height)
        if (resized) revalidate()
    }

    private fun displayShortcut(key: KeyStroke): String {
        val name =
            when (key.keyCode) {
                KeyEvent.VK_LEFT -> "←"
                KeyEvent.VK_RIGHT -> "→"
                KeyEvent.VK_UP -> "↑"
                KeyEvent.VK_DOWN -> "↓"
                else -> KeyEvent.getKeyText(key.keyCode)
            }
        val modifiers = InputEvent.getModifiersExText(key.modifiers)
        return if (modifiers.isEmpty()) name else "$modifiers+$name"
    }
}
