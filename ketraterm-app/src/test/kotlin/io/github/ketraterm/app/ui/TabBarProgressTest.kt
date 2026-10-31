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

import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import io.github.ketraterm.workspace.TerminalProfileKind
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

class TabBarProgressTest {
    @Test
    fun `selected accessible progress follows tab selection without changing labels`() =
        onEdt {
            val bar = TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> })
            bar.addTab(TabEntry("first", "First", TerminalProfileKind.DEFAULT))
            bar.addTab(TabEntry("second", "Second", TerminalProfileKind.DEFAULT))
            bar.updateProgress("first", TerminalProgress(TerminalProgressState.NORMAL, 25))
            bar.updateProgress("second", TerminalProgress(TerminalProgressState.WARNING, 75))
            bar.selectTab("first")
            assertEquals("First", bar.selectedTitle())
            assertEquals("[25%] First", bar.accessibleContext.accessibleDescription)
            bar.selectTab("second")
            assertEquals("Second", bar.selectedTitle())
            assertEquals("[Warning 75%] Second", bar.accessibleContext.accessibleDescription)
            bar.closeProgressAnimation()
        }

    @Test
    fun `progress decorates display without becoming part of the editable title`() =
        onEdt {
            val entry = TabEntry("build", "Build", TerminalProfileKind.DEFAULT)
            val bar = TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> })
            bar.addTab(entry)
            assertEquals("Build", entry.displayTitle)
            bar.updateProgress("build", TerminalProgress(TerminalProgressState.NORMAL, 50))
            assertEquals("Build", entry.title)
            assertEquals("[50%] Build", entry.displayTitle)
            bar.updateTitle("build", "Renamed")
            assertEquals("[50%] Renamed", entry.displayTitle)
            assertEquals("Renamed", bar.selectedTitle())
            bar.updateProgress("build", TerminalProgress(TerminalProgressState.HIDDEN, 0))
            assertEquals("Renamed", entry.displayTitle)
            bar.removeTab("build")
            bar.updateProgress("build", TerminalProgress(TerminalProgressState.NORMAL, 75))
            assertEquals(null, bar.selectedTitle())
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
