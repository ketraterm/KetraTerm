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

import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.*
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalDirectoryMenuItemTest {
    @ParameterizedTest
    @ValueSource(strings = ["success", "missing", "failure", "dismiss", "changed", "closed"])
    fun `blocked directory lookup leaves popup responsive and cannot publish obsolete actions`(outcome: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val expected = Path.of("captured-directory")
        var current = true
        val opened = mutableListOf<Path>()
        val (popup, item) =
            onEdt {
                val popup = JPopupMenu()
                val item =
                    LocalDirectoryMenuItem(popup, "file:///captured", { current }, opened::add, worker) {
                        assertFalse(SwingUtilities.isEventDispatchThread())
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        try {
                            if (outcome == "failure") error("directory unavailable")
                            if (outcome == "missing") null else expected
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                popup.add(item)
                popup.popupMenuListeners.forEach { it.popupMenuWillBecomeVisible(PopupMenuEvent(popup)) }
                popup to item
            }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            onEdt {
                assertFalse(item.isEnabled)
                if (outcome == "dismiss") popup.popupMenuListeners.forEach { it.popupMenuWillBecomeInvisible(PopupMenuEvent(popup)) }
                if (outcome == "changed" || outcome == "closed") current = false
            }
            release.countDown()
            finished.get(10, TimeUnit.SECONDS)
            // The executor barrier follows worker return and its EDT continuation dispatch.
            executor.submit {}.get(10, TimeUnit.SECONDS)
            onEdt {
                assertEquals(outcome == "success", item.isEnabled)
                item.doClick(0)
                assertEquals(if (outcome == "success") listOf(expected) else emptyList(), opened)
                // Even an already resolved action cannot launch for a different directory/pane.
                current = false
                item.doClick(0)
                assertEquals(if (outcome == "success") listOf(expected) else emptyList(), opened)
            }
        } finally {
            release.countDown()
            onEdt { popup.popupMenuListeners.forEach { it.popupMenuWillBecomeInvisible(PopupMenuEvent(popup)) } }
            worker.close()
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
