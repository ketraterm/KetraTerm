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

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.KeyStroke

/**
 * Host-owned terminal pane actions that can be bound by applications embedding
 * `SwingTerminal`.
 *
 * The reusable terminal component does not install shortcuts for these actions.
 * Hosts use this vocabulary to keep shortcut policy outside rendering/input
 * internals while sharing platform defaults across Swing-based hosts.
 */
public enum class SwingTerminalHostAction {
    /** Copy the current terminal selection to the host clipboard. */
    COPY_SELECTION,

    /** Paste host clipboard text into the terminal session. */
    PASTE_CLIPBOARD,

    /** Open the host-owned terminal search UI. */
    OPEN_SEARCH,

    /** Request suggestions for the active shell command line. */
    REQUEST_SUGGESTIONS,

    /** Select all retained terminal text. */
    SELECT_ALL,

    /** Request a foreground-program screen clear/redraw. */
    CLEAR_SCREEN,

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
public data class SwingTerminalHostShortcut(
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
    public fun keyStroke(): KeyStroke = KeyStroke.getKeyStroke(keyCode, modifiers)
}

/**
 * Immutable mapping from host-owned terminal actions to Swing shortcuts.
 *
 * Each action has at most one shortcut, and each shortcut belongs to at most one
 * action. Unspecified actions are unbound. Hosts explicitly install bindings
 * using [forEachShortcut] or dispatch key events using [actionFor].
 */
public class SwingTerminalHostShortcutMap private constructor(
    private val shortcuts: Array<SwingTerminalHostShortcut?>,
) {
    /**
     * Creates a detached snapshot of [shortcuts], including an empty map.
     * Subsequent changes to the supplied map do not affect this instance.
     *
     * @param shortcuts bindings using extended keyboard modifier masks.
     * @throws IllegalArgumentException if two actions share a shortcut or a
     * shortcut contains legacy, mouse-button, or unknown modifier bits.
     */
    public constructor(shortcuts: Map<SwingTerminalHostAction, SwingTerminalHostShortcut>) :
        this(arrayOfNulls(SwingTerminalHostAction.entries.size)) {
        for ((action, shortcut) in shortcuts) {
            validateShortcut(action, shortcut)
            this.shortcuts[action.ordinal] = shortcut
        }
    }

    /**
     * Returns the configured shortcut for [action].
     *
     * @param action host action to query.
     * @return configured shortcut, or `null` when the action is unbound.
     */
    public fun shortcut(action: SwingTerminalHostAction): SwingTerminalHostShortcut? = shortcuts[action.ordinal]

    /**
     * Returns a map with [action] bound to [shortcut], replacing its previous
     * binding. This instance is unchanged.
     *
     * To reassign another action's shortcut, first remove that binding with
     * [withoutShortcut]. An unchanged binding may return this instance.
     *
     * @param action host action to bind.
     * @param shortcut binding using an extended keyboard modifier mask.
     * @return map containing the updated binding.
     * @throws IllegalArgumentException if another action has this shortcut or
     * it contains legacy, mouse-button, or unknown modifier bits.
     */
    public fun withShortcut(
        action: SwingTerminalHostAction,
        shortcut: SwingTerminalHostShortcut,
    ): SwingTerminalHostShortcutMap {
        validateShortcut(action, shortcut)
        if (shortcuts[action.ordinal] == shortcut) return this
        val updated = shortcuts.copyOf()
        updated[action.ordinal] = shortcut
        return SwingTerminalHostShortcutMap(updated)
    }

    /**
     * Returns a map with [action] unbound. This instance is unchanged.
     *
     * @param action host action to unbind.
     * @return map without the binding, or this instance if already unbound.
     */
    public fun withoutShortcut(action: SwingTerminalHostAction): SwingTerminalHostShortcutMap {
        if (shortcuts[action.ordinal] == null) return this
        val updated = shortcuts.copyOf()
        updated[action.ordinal] = null
        return SwingTerminalHostShortcutMap(updated)
    }

    private fun validateShortcut(
        action: SwingTerminalHostAction,
        shortcut: SwingTerminalHostShortcut,
    ) {
        require(shortcut.modifiers and RELEVANT_MODIFIERS == shortcut.modifiers) {
            "Shortcut modifiers must contain only extended keyboard modifier bits, was ${shortcut.modifiers}"
        }
        var index = 0
        while (index < shortcuts.size) {
            require(index == action.ordinal || shortcuts[index] != shortcut) {
                "Shortcut $shortcut is already bound to ${SwingTerminalHostAction.entries[index]}"
            }
            index++
        }
    }

    /**
     * Returns the host action requested by [keyCode] and [modifiersEx].
     *
     * @param keyCode Swing virtual key code.
     * @param modifiersEx extended Swing modifier mask; non-keyboard bits are ignored.
     * @return matching action, or `null` when the key event is not a host
     * terminal shortcut.
     */
    public fun actionFor(
        keyCode: Int,
        modifiersEx: Int,
    ): SwingTerminalHostAction? {
        val normalizedModifiers = modifiersEx and RELEVANT_MODIFIERS
        var index = 0
        while (index < shortcuts.size) {
            val shortcut = shortcuts[index]
            if (shortcut != null && shortcut.keyCode == keyCode && shortcut.modifiers == normalizedModifiers) {
                return SwingTerminalHostAction.entries[index]
            }
            index++
        }
        return null
    }

    /**
     * Invokes [consumer] once for each configured binding in action enum order.
     * Unbound actions are omitted.
     *
     * @param consumer callback receiving each action and shortcut.
     */
    public fun forEachShortcut(consumer: (SwingTerminalHostAction, SwingTerminalHostShortcut) -> Unit) {
        var index = 0
        while (index < shortcuts.size) {
            val shortcut = shortcuts[index]
            if (shortcut != null) consumer(SwingTerminalHostAction.entries[index], shortcut)
            index++
        }
    }

    public companion object {
        /**
         * Returns platform-default terminal pane shortcuts for this JVM host.
         *
         * @return platform-default shortcut map.
         */
        @JvmStatic
        public fun platformDefault(): SwingTerminalHostShortcutMap = platformDefault(System.getProperty("os.name").orEmpty())

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
            map[SwingTerminalHostAction.REQUEST_SUGGESTIONS] =
                SwingTerminalHostShortcut(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)
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
