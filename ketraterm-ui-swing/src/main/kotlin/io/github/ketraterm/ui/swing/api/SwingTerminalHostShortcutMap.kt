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
package io.github.ketraterm.ui.swing.api

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.KeyStroke

/**
 * Host-owned terminal pane actions that can be bound by applications embedding
 * [SwingTerminal].
 *
 * The reusable terminal component does not install shortcuts for these actions.
 * Hosts use this vocabulary to keep shortcut policy outside rendering/input
 * internals while sharing platform defaults across Swing-based hosts.
 */
enum class SwingTerminalHostAction {
    /** Copy the current terminal selection to the host clipboard. */
    COPY_SELECTION,

    /** Paste host clipboard text into the terminal session. */
    PASTE_CLIPBOARD,

    /** Open the terminal search UI. */
    OPEN_SEARCH,

    /** Scroll one visible terminal page away from the live viewport. */
    SCROLL_PAGE_UP,

    /** Scroll one visible terminal page toward the live viewport. */
    SCROLL_PAGE_DOWN,
}

/**
 * Immutable Swing keystroke descriptor for a host-owned terminal action.
 *
 * @property keyCode Swing virtual key code.
 * @property modifiers extended Swing modifier mask.
 */
data class SwingTerminalHostShortcut(
    val keyCode: Int,
    val modifiers: Int,
) {
    init {
        require(keyCode > 0) { "keyCode must be positive, was $keyCode" }
    }

    /**
     * Returns this shortcut as a Swing [KeyStroke].
     *
     * @return Swing key stroke for key-binding registration.
     */
    fun keyStroke(): KeyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)
}

/**
 * Immutable mapping from host-owned terminal actions to Swing shortcuts.
 *
 * @property shortcuts action-to-shortcut map.
 */
class SwingTerminalHostShortcutMap private constructor(
    private val shortcuts: EnumMap<SwingTerminalHostAction, SwingTerminalHostShortcut>,
) {
    /**
     * Returns the configured shortcut for [action].
     *
     * @param action host action to query.
     * @return configured shortcut.
     */
    fun shortcut(action: SwingTerminalHostAction): SwingTerminalHostShortcut = shortcuts.getValue(action)

    /**
     * Returns the host action requested by [keyCode] and [modifiersEx].
     *
     * @param keyCode Swing virtual key code.
     * @param modifiersEx extended Swing modifier mask.
     * @return matching action, or `null` when the key event is not a host terminal shortcut.
     */
    fun actionFor(
        keyCode: Int,
        modifiersEx: Int,
    ): SwingTerminalHostAction? {
        val normalizedModifiers = modifiersEx and RELEVANT_MODIFIERS
        for (entry in shortcuts.entries) {
            val shortcut = entry.value
            if (shortcut.keyCode == keyCode && shortcut.modifiers == normalizedModifiers) {
                return entry.key
            }
        }
        return null
    }

    /**
     * Invokes [consumer] for each configured action shortcut.
     *
     * @param consumer callback receiving each action and shortcut.
     */
    fun forEachShortcut(consumer: (SwingTerminalHostAction, SwingTerminalHostShortcut) -> Unit) {
        for (entry in shortcuts.entries) {
            consumer(entry.key, entry.value)
        }
    }

    companion object {
        /**
         * Returns platform-default terminal pane shortcuts for this JVM host.
         *
         * @return platform-default shortcut map.
         */
        @JvmStatic
        fun platformDefault(): SwingTerminalHostShortcutMap = platformDefault(System.getProperty("os.name").orEmpty())

        internal fun platformDefault(osName: String): SwingTerminalHostShortcutMap {
            val normalized = osName.lowercase(Locale.ROOT)
            val map = EnumMap<SwingTerminalHostAction, SwingTerminalHostShortcut>(SwingTerminalHostAction::class.java)
            val menuModifier =
                if (normalized.contains("mac") || normalized.contains("darwin")) {
                    InputEvent.META_DOWN_MASK
                } else {
                    InputEvent.CTRL_DOWN_MASK
                }
            val clipboardModifier =
                when {
                    normalized.contains("mac") || normalized.contains("darwin") -> InputEvent.META_DOWN_MASK
                    normalized.contains("win") -> InputEvent.CTRL_DOWN_MASK
                    else -> InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
                }

            map[SwingTerminalHostAction.COPY_SELECTION] =
                SwingTerminalHostShortcut(KeyEvent.VK_C, clipboardModifier)
            map[SwingTerminalHostAction.PASTE_CLIPBOARD] =
                SwingTerminalHostShortcut(KeyEvent.VK_V, clipboardModifier)
            map[SwingTerminalHostAction.OPEN_SEARCH] =
                SwingTerminalHostShortcut(KeyEvent.VK_F, menuModifier or InputEvent.SHIFT_DOWN_MASK)
            map[SwingTerminalHostAction.SCROLL_PAGE_UP] =
                SwingTerminalHostShortcut(KeyEvent.VK_PAGE_UP, InputEvent.SHIFT_DOWN_MASK)
            map[SwingTerminalHostAction.SCROLL_PAGE_DOWN] =
                SwingTerminalHostShortcut(KeyEvent.VK_PAGE_DOWN, InputEvent.SHIFT_DOWN_MASK)
            return SwingTerminalHostShortcutMap(map)
        }

        private const val RELEVANT_MODIFIERS =
            InputEvent.SHIFT_DOWN_MASK or
                InputEvent.CTRL_DOWN_MASK or
                InputEvent.META_DOWN_MASK or
                InputEvent.ALT_DOWN_MASK or
                InputEvent.ALT_GRAPH_DOWN_MASK
    }
}
