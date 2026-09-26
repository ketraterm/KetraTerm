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

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JTextArea
import kotlin.test.*

class SwingClipboardReadPromptTest {
    @Test
    fun cancellationCloseAndDuplicateDecisionsCannotGrantAReplacementRequest() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val prompt = SwingClipboardReadPrompt(JPanel())
                val first = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("First") }
                first.cancelAndJoin()
                prompt.click("Allow once")
                assertFalse(prompt.component.isVisible)
                val second = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("Second") }
                prompt.click("Deny")
                prompt.click("Allow once")
                assertFalse(second.await())
                val third = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("Third") }
                prompt.close()
                assertFailsWith<CancellationException> { third.await() }
                assertFalse(prompt.component.isVisible)
                assertFalse(prompt.request("Closed"))
                assertTrue(prompt.isBlocked)
            }
        }

    @Test
    fun promptTreatsMessageAsPlainTextAndDoesNotResizeTerminal() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val content = JPanel().apply { preferredSize = Dimension(640, 400) }
                val prompt = SwingClipboardReadPrompt(JPanel())
                val pane = SwingTerminalOverlayPane(content, prompt.component)
                pane.setSize(640, 400)
                pane.doLayout()
                val originalBounds = content.bounds
                val message = "<html><img src='https://invalid.example'> terminal"
                val pending = async(start = CoroutineStart.UNDISPATCHED) { prompt.request(message) }
                pane.doLayout()
                assertEquals(originalBounds, content.bounds)
                assertEquals(Dimension(640, 400), pane.preferredSize)
                assertEquals(
                    message,
                    prompt.component.components
                        .filterIsInstance<JTextArea>()
                        .single()
                        .text,
                )
                assertFalse(prompt.request("Concurrent replacement"))
                assertTrue(prompt.dismiss())
                assertFalse(pending.await())
                prompt.close()
            }
        }
}
