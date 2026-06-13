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
package io.github.jvterm.ui.swing.settings

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.*

/**
 * Platform clipboard key bindings for the reusable Swing terminal.
 *
 * The default follows terminal conventions for the current host OS. Embedders
 * can replace this value when their shell, IDE, or application has a different
 * native shortcut policy.
 *
 * @property copyKey key code that requests copy.
 * @property copyModifiers exact modifier mask required for copy.
 * @property pasteKey key code that requests paste.
 * @property pasteModifiers exact modifier mask required for paste.
 */
data class TerminalClipboardShortcuts(
    val copyKey: Int,
    val copyModifiers: Int,
    val pasteKey: Int,
    val pasteModifiers: Int,
) {
    init {
        require(copyKey > 0) { "copyKey must be positive, was $copyKey" }
        require(pasteKey > 0) { "pasteKey must be positive, was $pasteKey" }
    }

    /**
     * Returns the clipboard action requested by [keyCode] and [modifiersEx].
     *
     * @param keyCode Swing key code.
     * @param modifiersEx extended Swing modifier mask.
     * @return requested action, or [TerminalClipboardAction.NONE].
     */
    fun actionFor(
        keyCode: Int,
        modifiersEx: Int,
    ): TerminalClipboardAction {
        val normalizedModifiers = modifiersEx and RELEVANT_MODIFIERS
        return when {
            keyCode == copyKey && normalizedModifiers == copyModifiers -> TerminalClipboardAction.COPY
            keyCode == pasteKey && normalizedModifiers == pasteModifiers -> TerminalClipboardAction.PASTE
            else -> TerminalClipboardAction.NONE
        }
    }

    companion object {
        /**
         * Returns the default terminal clipboard shortcuts for this JVM host.
         */
        @JvmStatic
        fun platformDefault(): TerminalClipboardShortcuts = platformDefault(System.getProperty("os.name").orEmpty())

        internal fun platformDefault(osName: String): TerminalClipboardShortcuts {
            val normalized = osName.lowercase(Locale.ROOT)
            return when {
                normalized.contains("mac") || normalized.contains("darwin") -> macOs()
                normalized.contains("win") -> windows()
                else -> linuxAndUnix()
            }
        }

        /**
         * Windows terminal-style clipboard shortcuts.
         */
        @JvmStatic
        fun windows(): TerminalClipboardShortcuts =
            TerminalClipboardShortcuts(
                copyKey = KeyEvent.VK_C,
                copyModifiers = InputEvent.CTRL_DOWN_MASK,
                pasteKey = KeyEvent.VK_V,
                pasteModifiers = InputEvent.CTRL_DOWN_MASK,
            )

        /**
         * macOS menu-shortcut clipboard bindings.
         */
        @JvmStatic
        fun macOs(): TerminalClipboardShortcuts =
            TerminalClipboardShortcuts(
                copyKey = KeyEvent.VK_C,
                copyModifiers = InputEvent.META_DOWN_MASK,
                pasteKey = KeyEvent.VK_V,
                pasteModifiers = InputEvent.META_DOWN_MASK,
            )

        /**
         * Linux and Unix terminal clipboard bindings.
         */
        @JvmStatic
        fun linuxAndUnix(): TerminalClipboardShortcuts {
            val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            return TerminalClipboardShortcuts(
                copyKey = KeyEvent.VK_C,
                copyModifiers = modifiers,
                pasteKey = KeyEvent.VK_V,
                pasteModifiers = modifiers,
            )
        }

        private const val RELEVANT_MODIFIERS =
            InputEvent.SHIFT_DOWN_MASK or
                InputEvent.CTRL_DOWN_MASK or
                InputEvent.META_DOWN_MASK or
                InputEvent.ALT_DOWN_MASK or
                InputEvent.ALT_GRAPH_DOWN_MASK
    }
}

/**
 * Clipboard action requested by a key event.
 */
enum class TerminalClipboardAction {
    /** No clipboard action was requested. */
    NONE,

    /** Copy the current terminal selection to the clipboard. */
    COPY,

    /** Paste clipboard text into the terminal session. */
    PASTE,
}
