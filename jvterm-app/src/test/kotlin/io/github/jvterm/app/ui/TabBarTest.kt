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
package io.github.jvterm.app.ui

import io.github.jvterm.workspace.TerminalProfileKind
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class TabBarTest {
    @Test
    fun tabWidthLockIsAppliedOnHoverCloseAndClearedOnExit() {
        val tabBar =
            TabBar(
                onTabSelected = {},
                onTabClose = {},
                onNewTab = {},
                onMenuClick = { _, _ -> },
                onTabColorChanged = { _, _ -> },
                onTabRenameRequested = { _, _ -> },
            )

        tabBar.setSize(600, 40)

        val tab1 = TabEntry("1", "Tab 1", TerminalProfileKind.DEFAULT)
        val tab2 = TabEntry("2", "Tab 2", TerminalProfileKind.DEFAULT)
        val tab3 = TabEntry("3", "Tab 3", TerminalProfileKind.DEFAULT)

        tabBar.addTab(tab1)
        tabBar.addTab(tab2)
        tabBar.addTab(tab3)

        val graphics =
            java.awt.image
                .BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                .createGraphics()
        tabBar.paint(graphics)

        // Trigger simulated mouse move to hover
        val mouseEventMoved =
            MouseEvent(
                tabBar,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                100,
                20,
                0,
                false,
            )
        for (l in tabBar.mouseMotionListeners) {
            l.mouseMoved(mouseEventMoved)
        }

        // Remove a tab while hovering
        tabBar.removeTab("3")

        // Under 600px width with 3 tabs, the tabs were shrunk to 174px each.
        // Lock width should be 174px. Preferred size should be based on 2 tabs of 174px.
        // 2 * 174 + 4 (gap) + 68 (static width) = 420px.
        assertEquals(420, tabBar.preferredSize.width)

        // Trigger mouse exit to clear lock
        val mouseEventExited =
            MouseEvent(
                tabBar,
                MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(),
                0,
                -1,
                -1,
                0,
                false,
            )
        for (l in tabBar.mouseListeners) {
            l.mouseExited(mouseEventExited)
        }

        // Now unlocked, remaining 2 tabs expand to preferred 220px:
        // 2 * 220 + 4 (gap) + 68 (static width) = 512px.
        assertEquals(512, tabBar.preferredSize.width)
    }

    @Test
    fun tabWidthLockLimitsTabExpansionInNarrowTabBar() {
        val tabBar =
            TabBar(
                onTabSelected = {},
                onTabClose = {},
                onNewTab = {},
                onMenuClick = { _, _ -> },
                onTabColorChanged = { _, _ -> },
                onTabRenameRequested = { _, _ -> },
            )

        // Narrow size: 300px width (where tabs will be shrunk)
        // Space for tabs = 300 - 68 (static width) = 232px
        // With 2 tabs: 232 - 4 (gap) = 228px. Each gets 114px.
        tabBar.setSize(300, 40)

        val tab1 = TabEntry("1", "Tab 1", TerminalProfileKind.DEFAULT)
        val tab2 = TabEntry("2", "Tab 2", TerminalProfileKind.DEFAULT)

        tabBar.addTab(tab1)
        tabBar.addTab(tab2)

        val graphics =
            java.awt.image
                .BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                .createGraphics()
        tabBar.paint(graphics)

        // Trigger simulated mouse entered
        val mouseEventEntered =
            MouseEvent(
                tabBar,
                MouseEvent.MOUSE_ENTERED,
                System.currentTimeMillis(),
                0,
                50,
                20,
                0,
                false,
            )
        for (l in tabBar.mouseListeners) {
            l.mouseEntered(mouseEventEntered)
        }

        // Remove tab 2 while hovered. The remaining tab 1 should lock to its previous width (114px)
        tabBar.removeTab("2")
        tabBar.paint(graphics)

        // While locked, preferred size should be based on 1 tab of 114px:
        // 1 * 114 + 68 (static width) = 182px
        assertEquals(182, tabBar.preferredSize.width)

        // Let's trigger mouse exited.
        val mouseEventExited =
            MouseEvent(
                tabBar,
                MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(),
                0,
                -1,
                -1,
                0,
                false,
            )
        for (l in tabBar.mouseListeners) {
            l.mouseExited(mouseEventExited)
        }

        // Once mouse exits, lock is released and remaining tab goes to its preferred width (220px)
        // 1 * 220 + 68 (static width) = 288px
        assertEquals(288, tabBar.preferredSize.width)
    }
}
