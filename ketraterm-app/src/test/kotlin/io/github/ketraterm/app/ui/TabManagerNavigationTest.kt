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

import io.github.ketraterm.app.config.KetraTermConfig
import io.github.ketraterm.app.config.KetraTermConfigManager
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.workspace.TerminalProfile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.CardLayout
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the installed product key dispatcher, actual panes, and PTY-backed tab lifecycle. */
@Tag("native")
class TabManagerNavigationTest {
    @Test
    fun `control tab follows the displayed tabs through wrapping and deletion`(
        @TempDir directory: Path,
    ) {
        val command =
            if (System.getProperty("os.name").startsWith("Windows")) {
                listOf(requireNotNull(System.getenv("ComSpec")), "/d", "/q", "/k")
            } else {
                listOf("/bin/sh")
            }
        val profile = TerminalProfile("navigation", "Navigation", command, workingDirectory = directory)
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(KetraTermConfig(cursorBlinkMillis = 0, showForegroundProcessName = false))
        val task =
            FutureTask {
                val previousFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                val focusManager = NavigationFocusManager()
                KeyboardFocusManager.setCurrentKeyboardFocusManager(focusManager)
                val frame = JFrame()
                var manager: TabManager? = null
                try {
                    val tabBar = TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> })
                    val current = TabManager(frame, tabBar, JPanel(CardLayout()), KetraTermSettings(configManager), { profile }, { true })
                    manager = current
                    val ids =
                        // Multiple hash buckets are needed: the first few generated IDs can coincide cyclically with display order.
                        List(9) {
                            assertTrue(current.openTab(profile))
                            requireNotNull(tabBar.selectedId())
                        }

                    fun press(
                        expected: String?,
                        backwards: Boolean = false,
                    ) {
                        assertTrue(focusManager.press(frame, backwards))
                        assertEquals(expected, tabBar.selectedId())
                        assertEquals(expected, current.selectedPane?.tab?.id)
                    }

                    fun cycle(order: List<String>) {
                        current.selectTab(order.first())
                        for (id in order.drop(1) + order.first()) press(id)
                        for (id in order.drop(1).reversed() + order.first()) press(id, backwards = true)
                    }
                    cycle(ids)
                    assertTrue(current.closeTab(ids[1]))
                    cycle(ids.filter { it != ids[1] })
                    assertTrue(current.closeTab(ids[0]))
                    cycle(ids.drop(2))
                    for (id in ids.drop(3)) assertTrue(current.closeTab(id))
                    press(ids[2])
                    press(ids[2], backwards = true)
                    assertTrue(current.closeTab(ids[2]))
                    press(null)
                    press(null, backwards = true)
                } finally {
                    try {
                        manager?.closeAllTabsWithoutConfirmation()
                    } finally {
                        frame.dispose()
                        KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager)
                    }
                }
            }
        SwingUtilities.invokeLater(task)
        task.get(30, TimeUnit.SECONDS)
    }

    private class NavigationFocusManager : DefaultKeyboardFocusManager() {
        fun press(
            frame: JFrame,
            backwards: Boolean,
        ): Boolean {
            val modifiers = InputEvent.CTRL_DOWN_MASK or (if (backwards) InputEvent.SHIFT_DOWN_MASK else 0)
            val event = KeyEvent(frame, KeyEvent.KEY_PRESSED, 0L, modifiers, KeyEvent.VK_TAB, '\t')
            return keyEventDispatchers.orEmpty().any { it.dispatchKeyEvent(event) }
        }
    }
}
