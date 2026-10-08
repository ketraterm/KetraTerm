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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class SwingTerminalHostShortcutMapTest {
    @Test
    fun `windows defaults use ctrl clipboard shortcuts`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("Windows 11")

        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
        assertEquals(SwingTerminalHostAction.PASTE_CLIPBOARD, shortcuts.actionFor(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
        assertNull(shortcuts.actionFor(KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `linux defaults use ctrl shift clipboard shortcuts`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("Linux")
        val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, modifiers))
        assertEquals(SwingTerminalHostAction.PASTE_CLIPBOARD, shortcuts.actionFor(KeyEvent.VK_V, modifiers))
        assertNull(shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `mac defaults use meta clipboard and search shortcuts`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("macOS")

        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, InputEvent.META_DOWN_MASK))
        assertEquals(
            SwingTerminalHostAction.OPEN_SEARCH,
            shortcuts.actionFor(KeyEvent.VK_F, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
    }

    @Test
    fun `page scroll defaults use shift page keys`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("Windows 11")

        assertEquals(SwingTerminalHostAction.SCROLL_PAGE_UP, shortcuts.actionFor(KeyEvent.VK_PAGE_UP, InputEvent.SHIFT_DOWN_MASK))
        assertEquals(SwingTerminalHostAction.SCROLL_PAGE_DOWN, shortcuts.actionFor(KeyEvent.VK_PAGE_DOWN, InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `manual suggestions use ctrl space on every platform`() {
        for (osName in listOf("Windows 11", "Linux", "macOS")) {
            val shortcuts = SwingTerminalHostShortcutMap.platformDefault(osName)

            assertEquals(
                SwingTerminalHostAction.REQUEST_SUGGESTIONS,
                shortcuts.actionFor(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK),
                osName,
            )
        }
    }

    @Test
    fun `platform defaults leave optional actions unbound`() {
        for (osName in listOf("Windows 11", "Linux", "macOS")) {
            val shortcuts = SwingTerminalHostShortcutMap.platformDefault(osName)

            assertNull(shortcuts.shortcut(SwingTerminalHostAction.SELECT_ALL), osName)
            assertNull(shortcuts.shortcut(SwingTerminalHostAction.CLEAR_SCREEN), osName)
        }
    }

    @Test
    fun `custom maps may contain any subset of actions including none`() {
        val empty = SwingTerminalHostShortcutMap(emptyMap())
        val selectAll = SwingTerminalHostShortcut(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)
        val custom = SwingTerminalHostShortcutMap(mapOf(SwingTerminalHostAction.SELECT_ALL to selectAll))
        var emptyBindingCount = 0

        empty.forEachShortcut { _, _ -> emptyBindingCount++ }

        assertEquals(0, emptyBindingCount)
        for (action in SwingTerminalHostAction.entries) {
            assertNull(empty.shortcut(action), action.name)
        }
        assertNull(empty.actionFor(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK))
        assertEquals(selectAll, custom.shortcut(SwingTerminalHostAction.SELECT_ALL))
        assertEquals(SwingTerminalHostAction.SELECT_ALL, custom.actionFor(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK))
        assertNull(custom.shortcut(SwingTerminalHostAction.COPY_SELECTION))
    }

    @Test
    fun `construction snapshots the source map`() {
        val copy = SwingTerminalHostShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)
        val source = mutableMapOf(SwingTerminalHostAction.COPY_SELECTION to copy)
        val shortcuts = SwingTerminalHostShortcutMap(source)

        source.clear()
        source[SwingTerminalHostAction.PASTE_CLIPBOARD] = SwingTerminalHostShortcut(KeyEvent.VK_V, InputEvent.ALT_DOWN_MASK)

        assertEquals(copy, shortcuts.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
        assertNull(shortcuts.shortcut(SwingTerminalHostAction.PASTE_CLIPBOARD))
        assertNull(shortcuts.actionFor(KeyEvent.VK_V, InputEvent.ALT_DOWN_MASK))
    }

    @Test
    fun `copy operations bind rebind and unbind without changing earlier maps`() {
        val original = SwingTerminalHostShortcutMap.platformDefault("Windows 11")
        val originalCopy = original.shortcut(SwingTerminalHostAction.COPY_SELECTION)
        val originalPaste = original.shortcut(SwingTerminalHostAction.PASTE_CLIPBOARD)
        val selectAll = SwingTerminalHostShortcut(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)
        val reboundCopy = SwingTerminalHostShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)

        val bound = original.withShortcut(SwingTerminalHostAction.SELECT_ALL, selectAll)
        val rebound = bound.withShortcut(SwingTerminalHostAction.COPY_SELECTION, reboundCopy)
        val unbound = rebound.withoutShortcut(SwingTerminalHostAction.SELECT_ALL)

        assertNull(original.shortcut(SwingTerminalHostAction.SELECT_ALL))
        assertEquals(originalCopy, original.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertEquals(originalCopy, bound.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertEquals(selectAll, bound.shortcut(SwingTerminalHostAction.SELECT_ALL))
        assertEquals(selectAll, rebound.shortcut(SwingTerminalHostAction.SELECT_ALL))
        assertEquals(reboundCopy, rebound.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertNull(rebound.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
        assertEquals(SwingTerminalHostAction.COPY_SELECTION, rebound.actionFor(reboundCopy.keyCode, reboundCopy.modifiers))
        assertEquals(originalPaste, rebound.shortcut(SwingTerminalHostAction.PASTE_CLIPBOARD))
        assertEquals(reboundCopy, unbound.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertNull(unbound.shortcut(SwingTerminalHostAction.SELECT_ALL))
        assertNull(unbound.actionFor(selectAll.keyCode, selectAll.modifiers))
        assertEquals(
            originalPaste,
            unbound.withoutShortcut(SwingTerminalHostAction.SELECT_ALL).shortcut(SwingTerminalHostAction.PASTE_CLIPBOARD),
        )
    }

    @Test
    fun `shortcut collisions are rejected at construction and update`() {
        val shortcut = SwingTerminalHostShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)
        val shortcuts = SwingTerminalHostShortcutMap(mapOf(SwingTerminalHostAction.COPY_SELECTION to shortcut))

        assertThrows(IllegalArgumentException::class.java) {
            SwingTerminalHostShortcutMap(
                mapOf(
                    SwingTerminalHostAction.COPY_SELECTION to shortcut,
                    SwingTerminalHostAction.PASTE_CLIPBOARD to shortcut,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            shortcuts.withShortcut(SwingTerminalHostAction.PASTE_CLIPBOARD, shortcut)
        }

        val sameBinding = shortcuts.withShortcut(SwingTerminalHostAction.COPY_SELECTION, shortcut)
        assertEquals(shortcut, sameBinding.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertEquals(SwingTerminalHostAction.COPY_SELECTION, sameBinding.actionFor(shortcut.keyCode, shortcut.modifiers))
        assertNull(shortcuts.shortcut(SwingTerminalHostAction.PASTE_CLIPBOARD))
    }

    @Test
    fun `removing a binding allows its shortcut to be reassigned`() {
        val shortcut = SwingTerminalHostShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)
        val original = SwingTerminalHostShortcutMap(mapOf(SwingTerminalHostAction.COPY_SELECTION to shortcut))

        val reassigned =
            original
                .withoutShortcut(SwingTerminalHostAction.COPY_SELECTION)
                .withShortcut(SwingTerminalHostAction.PASTE_CLIPBOARD, shortcut)

        assertNull(reassigned.shortcut(SwingTerminalHostAction.COPY_SELECTION))
        assertEquals(shortcut, reassigned.shortcut(SwingTerminalHostAction.PASTE_CLIPBOARD))
        assertEquals(SwingTerminalHostAction.PASTE_CLIPBOARD, reassigned.actionFor(shortcut.keyCode, shortcut.modifiers))
        assertEquals(SwingTerminalHostAction.COPY_SELECTION, original.actionFor(shortcut.keyCode, shortcut.modifiers))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `map rejects non keyboard extended modifier bits without rewriting descriptors`() {
        val empty = SwingTerminalHostShortcutMap(emptyMap())
        val invalidBits = listOf(InputEvent.SHIFT_MASK, InputEvent.BUTTON1_DOWN_MASK, 1 shl 30)

        for (invalidBit in invalidBits) {
            val modifiers = InputEvent.CTRL_DOWN_MASK or invalidBit
            val shortcut = SwingTerminalHostShortcut(KeyEvent.VK_C, modifiers)

            assertThrows(IllegalArgumentException::class.java) {
                SwingTerminalHostShortcutMap(mapOf(SwingTerminalHostAction.COPY_SELECTION to shortcut))
            }
            assertThrows(IllegalArgumentException::class.java) {
                empty.withShortcut(SwingTerminalHostAction.COPY_SELECTION, shortcut)
            }
            assertEquals(modifiers, shortcut.modifiers)
        }
    }

    @Test
    fun `map accepts unmodified shortcuts and every extended keyboard modifier`() {
        val keyboardModifiers =
            listOf(
                InputEvent.SHIFT_DOWN_MASK,
                InputEvent.CTRL_DOWN_MASK,
                InputEvent.META_DOWN_MASK,
                InputEvent.ALT_DOWN_MASK,
                InputEvent.ALT_GRAPH_DOWN_MASK,
            )
        val allModifiers = keyboardModifiers.fold(0) { combined, modifier -> combined or modifier }

        for (modifiers in listOf(0) + keyboardModifiers + allModifiers) {
            val shortcut = SwingTerminalHostShortcut(KeyEvent.VK_A, modifiers)
            val shortcuts = SwingTerminalHostShortcutMap(mapOf(SwingTerminalHostAction.SELECT_ALL to shortcut))

            assertEquals(shortcut, shortcuts.shortcut(SwingTerminalHostAction.SELECT_ALL))
            assertEquals(SwingTerminalHostAction.SELECT_ALL, shortcuts.actionFor(KeyEvent.VK_A, modifiers))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `event matching ignores other event bits and matches keyboard modifiers exactly`() {
        val shortcuts =
            SwingTerminalHostShortcutMap(
                mapOf(SwingTerminalHostAction.COPY_SELECTION to SwingTerminalHostShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)),
            )
        val irrelevantBits = InputEvent.BUTTON1_DOWN_MASK or InputEvent.SHIFT_MASK or (1 shl 30)

        assertEquals(
            SwingTerminalHostAction.COPY_SELECTION,
            shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or irrelevantBits),
        )
        assertNull(shortcuts.actionFor(KeyEvent.VK_C, 0))
        assertNull(shortcuts.actionFor(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
        for (extraModifier in listOf(
            InputEvent.SHIFT_DOWN_MASK,
            InputEvent.META_DOWN_MASK,
            InputEvent.ALT_DOWN_MASK,
            InputEvent.ALT_GRAPH_DOWN_MASK,
        )) {
            assertNull(shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or extraModifier), extraModifier.toString())
        }
    }

    @Test
    fun `iteration visits each bound action once in enum order with consistent lookups`() {
        val copy = SwingTerminalHostShortcut(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)
        val selectAll = SwingTerminalHostShortcut(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)
        val pageDown = SwingTerminalHostShortcut(KeyEvent.VK_PAGE_DOWN, InputEvent.SHIFT_DOWN_MASK)
        val shortcuts =
            SwingTerminalHostShortcutMap(
                linkedMapOf(
                    SwingTerminalHostAction.SCROLL_PAGE_DOWN to pageDown,
                    SwingTerminalHostAction.SELECT_ALL to selectAll,
                    SwingTerminalHostAction.COPY_SELECTION to copy,
                ),
            )
        val visited = mutableListOf<Pair<SwingTerminalHostAction, SwingTerminalHostShortcut>>()

        shortcuts.forEachShortcut { action, shortcut ->
            val keyStroke = shortcut.keyStroke()
            visited += action to shortcut
            assertEquals(shortcut, shortcuts.shortcut(action))
            assertEquals(action, shortcuts.actionFor(shortcut.keyCode, shortcut.modifiers))
            assertEquals(KeyStroke.getKeyStroke(shortcut.keyCode, shortcut.modifiers), keyStroke)
            assertEquals(action, shortcuts.actionFor(keyStroke.keyCode, keyStroke.modifiers))
        }

        assertEquals(
            listOf(
                SwingTerminalHostAction.COPY_SELECTION to copy,
                SwingTerminalHostAction.SELECT_ALL to selectAll,
                SwingTerminalHostAction.SCROLL_PAGE_DOWN to pageDown,
            ),
            visited,
        )
    }
}
