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

import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.SwingUtilities

/**
 * IntelliJ-hosted shortcut bindings for one terminal pane.
 *
 * IDE actions and shortcut policy stay in the plugin host. The reusable Swing
 * terminal remains responsible for painting, selection, terminal mouse
 * tracking, and terminal input encoding.
 */
internal class KetraTermTerminalShortcutController(
    private val pane: KetraTermTerminalPane,
) {
    private val keyEventDispatcher =
        KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (!focusOwnerIsInsidePane()) return@KeyEventDispatcher false
            handleKeyPressed(event)
        }

    private val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isConsumed) return
                if (!SwingUtilities.isMiddleMouseButton(event)) return
                if (!KetraTermIntellijSettings.getInstance().pasteOnMiddleClick()) return
                if (!pane.terminal.pasteClipboardText()) return
                event.consume()
            }
        }

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes host shortcut hooks installed for this pane.
     */
    fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
        pane.terminal.removeMouseListener(mouseListener)
    }

    private fun focusOwnerIsInsidePane(): Boolean {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return focusOwner === pane.component || SwingUtilities.isDescendingFrom(focusOwner, pane.component)
    }

    private fun handleKeyPressed(event: KeyEvent): Boolean {
        if (event.isConsumed || event.isAltGraphDown) return false
        val action = clipboardShortcuts.actionFor(event.keyCode, event.modifiersEx)
        val handled =
            when (action) {
                IntellijClipboardAction.COPY -> pane.terminal.copySelectionToClipboard()
                IntellijClipboardAction.PASTE -> pane.terminal.pasteClipboardText()
                IntellijClipboardAction.NONE -> handleNonClipboardShortcut(event)
            }
        if (!handled) return false
        event.consume()
        return true
    }

    private fun handleNonClipboardShortcut(event: KeyEvent): Boolean {
        if (handleViewportShortcut(event)) return true
        if (handleSearchShortcut(event)) return true
        return false
    }

    private fun handleViewportShortcut(event: KeyEvent): Boolean {
        if (!event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown) return false
        val rows = pane.terminal.visibleGridSize().height.coerceAtLeast(1)
        val deltaRows =
            when (event.keyCode) {
                KeyEvent.VK_PAGE_UP -> rows
                KeyEvent.VK_PAGE_DOWN -> -rows
                else -> return false
            }
        pane.terminal.scrollViewportBy(deltaRows.toDouble())
        return true
    }

    private fun handleSearchShortcut(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.VK_F || !event.isShiftDown) return false
        val modifiers = event.modifiersEx
        val isMenuShortcut = (modifiers and menuShortcutMask) == menuShortcutMask
        if (!isMenuShortcut) return false
        pane.terminal.openSearch()
        return true
    }

    private companion object {
        private val clipboardShortcuts = IntellijClipboardShortcuts.platformDefault()
        private val menuShortcutMask: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        }
    }
}

private data class IntellijClipboardShortcuts(
    private val copyKey: Int,
    private val copyModifiers: Int,
    private val pasteKey: Int,
    private val pasteModifiers: Int,
) {
    fun actionFor(
        keyCode: Int,
        modifiersEx: Int,
    ): IntellijClipboardAction {
        val normalizedModifiers = modifiersEx and RELEVANT_MODIFIERS
        return when {
            keyCode == copyKey && normalizedModifiers == copyModifiers -> IntellijClipboardAction.COPY
            keyCode == pasteKey && normalizedModifiers == pasteModifiers -> IntellijClipboardAction.PASTE
            else -> IntellijClipboardAction.NONE
        }
    }

    companion object {
        fun platformDefault(): IntellijClipboardShortcuts {
            val normalized = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return when {
                normalized.contains("mac") || normalized.contains("darwin") ->
                    IntellijClipboardShortcuts(
                        copyKey = KeyEvent.VK_C,
                        copyModifiers = InputEvent.META_DOWN_MASK,
                        pasteKey = KeyEvent.VK_V,
                        pasteModifiers = InputEvent.META_DOWN_MASK,
                    )
                normalized.contains("win") ->
                    IntellijClipboardShortcuts(
                        copyKey = KeyEvent.VK_C,
                        copyModifiers = InputEvent.CTRL_DOWN_MASK,
                        pasteKey = KeyEvent.VK_V,
                        pasteModifiers = InputEvent.CTRL_DOWN_MASK,
                    )
                else -> {
                    val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
                    IntellijClipboardShortcuts(
                        copyKey = KeyEvent.VK_C,
                        copyModifiers = modifiers,
                        pasteKey = KeyEvent.VK_V,
                        pasteModifiers = modifiers,
                    )
                }
            }
        }

        private const val RELEVANT_MODIFIERS =
            InputEvent.SHIFT_DOWN_MASK or
                InputEvent.CTRL_DOWN_MASK or
                InputEvent.META_DOWN_MASK or
                InputEvent.ALT_DOWN_MASK or
                InputEvent.ALT_GRAPH_DOWN_MASK
    }
}

private enum class IntellijClipboardAction {
    NONE,
    COPY,
    PASTE,
}
