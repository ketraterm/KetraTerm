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
import kotlin.test.*

class SwingClipboardReadPromptTest {
    @Test
    fun cancellationCloseAndStaleDecisionsCannotGrantAReplacementRequest() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val dialog = ClipboardDialogFixture()
                val prompt = dialog.prompt
                val first = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("First") }
                val staleDecision = dialog.decide
                first.cancelAndJoin()
                assertFalse(dialog.isVisible)
                val second = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("Second") }
                staleDecision(SwingClipboardReadPrompt.Decision.BLOCK.ordinal)
                assertFalse(prompt.isBlocked)
                assertFalse(second.isCompleted)
                dialog.click("Deny")
                dialog.click("Allow once")
                assertFalse(second.await())
                val third = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("Third") }
                prompt.close()
                assertFailsWith<CancellationException> { third.await() }
                assertFalse(dialog.isVisible)
                assertFalse(prompt.request("Closed"))
                assertTrue(prompt.isBlocked)
            }
        }

    @Test
    fun concurrentRequestCannotReplaceDialogAndEscapeDenies() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val dialog = ClipboardDialogFixture()
                val message = "<html><img src='https://invalid.example'> terminal"
                val pending = async(start = CoroutineStart.UNDISPATCHED) { dialog.prompt.request(message) }
                assertEquals(message, dialog.message)
                assertFalse(dialog.prompt.request("Concurrent replacement"))
                assertEquals(message, dialog.message)
                assertTrue(dialog.prompt.dismiss())
                assertFalse(pending.await())
                assertFalse(dialog.isVisible)
                dialog.prompt.close()
            }
        }
}
