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

import io.github.ketraterm.app.appMessages
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.*

class TerminalExitBannerTest {
    @Test
    fun `exit banner reports failure status and offers an explicit close action`() =
        onEdt {
            var closed = 0
            val banner = TerminalExitBanner { closed++ }
            assertFalse(banner.isVisible)
            banner.showExitStatus(7, null)
            assertTrue(banner.isVisible)
            val message = banner.components.filterIsInstance<JTextArea>().single()
            assertEquals(appMessages.text("session.exit.failed", 7), message.text)
            assertFalse(message.isEditable)
            assertTrue(message.lineWrap)
            assertEquals(Chrome.textPrimary, message.foreground)
            assertEquals(0, closed)
            banner.components
                .filterIsInstance<JButton>()
                .single()
                .doClick(0)
            assertEquals(1, closed)
        }

    @Test
    fun `failure details are bounded plain text with controls removed`() =
        onEdt {
            val banner = TerminalExitBanner {}
            banner.showExitStatus(null, IllegalStateException("<html>bad\u001b\nconnection"))
            val message = banner.components.filterIsInstance<JTextArea>().single()
            assertEquals(appMessages.text("session.exit.error", "<html>bad  connection"), message.text)
            banner.showExitStatus(null, IllegalStateException("😀".repeat(201)))
            assertEquals(appMessages.text("session.exit.error", "😀".repeat(200) + "…"), message.text)
            banner.showExitStatus(null, IllegalStateException(""))
            assertEquals(appMessages.text("session.exit.error", "IllegalStateException"), message.text)
            banner.showExitStatus(7, IllegalStateException("cleanup failed"))
            assertEquals(appMessages.text("session.exit.errorWithStatus", 7, "cleanup failed"), message.text)
        }

    @Test
    fun `unknown process status is reported without inventing an exit code`() =
        onEdt {
            val banner = TerminalExitBanner {}
            banner.showExitStatus(null, null)
            assertEquals(
                appMessages.text("session.exit.unknown"),
                banner.components
                    .filterIsInstance<JTextArea>()
                    .single()
                    .text,
            )
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
