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

import io.github.ketraterm.app.config.KetraTermSettings
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.SwingUtilities

/**
 * Standalone-host shortcut bindings for one terminal pane.
 *
 * The reusable `SwingTerminal` exposes terminal operations but does not decide
 * which keyboard or mouse gestures invoke application commands. This controller
 * keeps those bindings in the standalone host while leaving terminal keystrokes
 * to the reusable input encoder.
 */
internal class TerminalPaneShortcutController(
    private val pane: TerminalPane,
    private val settings: KetraTermSettings,
) {
    private val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isConsumed) return
                if (!SwingUtilities.isMiddleMouseButton(event)) return
                if (!settings.pasteOnMiddleClick) return
                if (!pane.terminal.pasteClipboardText()) return
                event.consume()
            }
        }

    init {
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes listeners installed by this controller.
     */
    fun dispose() {
        pane.terminal.removeMouseListener(mouseListener)
    }

    companion object {
        private val clipboardShortcuts = StandaloneClipboardShortcuts.platformDefault()
        private val menuShortcutMask: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
            Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        }

        /**
         * Handles a standalone terminal shortcut for [pane].
         *
         * @param event key event seen before the reusable terminal component.
         * @param pane active terminal pane.
         * @return `true` when the host command consumed the event.
         */
        fun handleKeyPressed(
            event: KeyEvent,
            pane: TerminalPane,
        ): Boolean {
            if (event.isConsumed || event.isAltGraphDown) return false
            val action = clipboardShortcuts.actionFor(event.keyCode, event.modifiersEx)
            val handled =
                when (action) {
                    StandaloneClipboardAction.COPY -> pane.terminal.copySelectionToClipboard()
                    StandaloneClipboardAction.PASTE -> pane.terminal.pasteClipboardText()
                    StandaloneClipboardAction.NONE -> handleNonClipboardShortcut(event, pane)
                }
            if (!handled) return false
            event.consume()
            return true
        }

        private fun handleNonClipboardShortcut(
            event: KeyEvent,
            pane: TerminalPane,
        ): Boolean {
            if (handleViewportShortcut(event, pane)) return true
            if (handleSearchShortcut(event, pane)) return true
            return false
        }

        private fun handleViewportShortcut(
            event: KeyEvent,
            pane: TerminalPane,
        ): Boolean {
            if (!event.isShiftDown || event.isControlDown || event.isAltDown || event.isMetaDown) return false
            val rows =
                pane.terminal
                    .visibleGridSize()
                    .height
                    .coerceAtLeast(1)
            val deltaRows =
                when (event.keyCode) {
                    KeyEvent.VK_PAGE_UP -> rows
                    KeyEvent.VK_PAGE_DOWN -> -rows
                    else -> return false
                }
            pane.terminal.scrollViewportBy(deltaRows.toDouble())
            return true
        }

        private fun handleSearchShortcut(
            event: KeyEvent,
            pane: TerminalPane,
        ): Boolean {
            if (event.keyCode != KeyEvent.VK_F || !event.isShiftDown) return false
            val modifiers = event.modifiersEx
            val isMenuShortcut = (modifiers and menuShortcutMask) == menuShortcutMask
            if (!isMenuShortcut) return false
            pane.terminal.openSearch()
            return true
        }
    }
}

internal data class StandaloneClipboardShortcuts(
    private val copyKey: Int,
    private val copyModifiers: Int,
    private val pasteKey: Int,
    private val pasteModifiers: Int,
) {
    fun actionFor(
        keyCode: Int,
        modifiersEx: Int,
    ): StandaloneClipboardAction {
        val normalizedModifiers = modifiersEx and RELEVANT_MODIFIERS
        return when {
            keyCode == copyKey && normalizedModifiers == copyModifiers -> StandaloneClipboardAction.COPY
            keyCode == pasteKey && normalizedModifiers == pasteModifiers -> StandaloneClipboardAction.PASTE
            else -> StandaloneClipboardAction.NONE
        }
    }

    companion object {
        fun platformDefault(): StandaloneClipboardShortcuts = platformDefault(System.getProperty("os.name").orEmpty())

        fun platformDefault(osName: String): StandaloneClipboardShortcuts {
            val normalized = osName.lowercase(Locale.ROOT)
            return when {
                normalized.contains("mac") || normalized.contains("darwin") ->
                    StandaloneClipboardShortcuts(
                        copyKey = KeyEvent.VK_C,
                        copyModifiers = InputEvent.META_DOWN_MASK,
                        pasteKey = KeyEvent.VK_V,
                        pasteModifiers = InputEvent.META_DOWN_MASK,
                    )
                normalized.contains("win") ->
                    StandaloneClipboardShortcuts(
                        copyKey = KeyEvent.VK_C,
                        copyModifiers = InputEvent.CTRL_DOWN_MASK,
                        pasteKey = KeyEvent.VK_V,
                        pasteModifiers = InputEvent.CTRL_DOWN_MASK,
                    )
                else -> {
                    val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
                    StandaloneClipboardShortcuts(
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

internal enum class StandaloneClipboardAction {
    NONE,
    COPY,
    PASTE,
}
