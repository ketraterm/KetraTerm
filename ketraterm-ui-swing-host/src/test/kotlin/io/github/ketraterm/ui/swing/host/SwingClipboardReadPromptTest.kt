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
import kotlinx.coroutines.test.StandardTestDispatcher
import javax.swing.SwingUtilities
import kotlin.test.*

class SwingClipboardReadPromptTest {
    @Test
    fun `synchronous decisions and reentrant closure dispose the returned handle once`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                for (closePane in listOf(false, true)) {
                    var closes = 0
                    lateinit var prompt: SwingClipboardReadPrompt
                    prompt =
                        SwingClipboardReadPrompt { _, decide ->
                            if (closePane) prompt.close() else decide(SwingClipboardReadPrompt.Decision.ALLOW_ONCE.ordinal)
                            AutoCloseable {
                                assertTrue(SwingUtilities.isEventDispatchThread())
                                closes++
                                decide(SwingClipboardReadPrompt.Decision.BLOCK.ordinal)
                            }
                        }
                    try {
                        if (closePane) {
                            assertFailsWith<CancellationException> { prompt.request("Consent") }
                        } else {
                            assertTrue(prompt.request("Consent"))
                        }
                        assertEquals(1, closes)
                        assertEquals(closePane, prompt.isBlocked)
                        assertFalse(prompt.dismiss())
                    } finally {
                        prompt.close()
                    }
                    assertEquals(1, closes)
                }
            }
        }

    @Test
    fun `presenter failure releases admission for the next request`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val failure = IllegalStateException("Cannot create dialog")
                var presentations = 0
                var closes = 0
                val prompt =
                    SwingClipboardReadPrompt { _, decide ->
                        if (++presentations == 1) throw failure
                        decide(SwingClipboardReadPrompt.Decision.DENY.ordinal)
                        AutoCloseable { closes++ }
                    }
                try {
                    assertSame(failure, assertFailsWith<IllegalStateException> { prompt.request("First") })
                    assertFalse(prompt.request("Second"))
                    assertEquals(2, presentations)
                    assertEquals(1, closes)
                } finally {
                    prompt.close()
                }
            }
        }

    @Test
    fun `disposal failure completes the caller and does not strand admission`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                supervisorScope {
                    val failure = IllegalStateException("Cannot dispose dialog")
                    var closes = 0
                    val prompt =
                        SwingClipboardReadPrompt { _, _ ->
                            AutoCloseable {
                                closes++
                                throw failure
                            }
                        }
                    try {
                        val first = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("First") }
                        assertTrue(prompt.dismiss())
                        val reported = assertFailsWith<IllegalStateException> { first.await() }
                        // Coroutine stack-trace recovery may copy the exception, retaining its cause.
                        assertTrue(generateSequence<Throwable>(reported) { it.cause }.any { it === failure })
                        assertFalse(prompt.dismiss())
                        val second = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("Second") }
                        assertSame(failure, assertFailsWith<IllegalStateException> { prompt.close() })
                        assertFailsWith<CancellationException> { second.await() }
                        assertEquals(2, closes)
                    } finally {
                        prompt.close()
                    }
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `terminal actions dispose before suspended callers resume`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                for (action in listOf("dismiss", "close", "allow", "deny", "block")) {
                    val dispatcher = StandardTestDispatcher()
                    val decisions = mutableListOf<(Int?) -> Unit>()
                    var closes = 0
                    val prompt =
                        SwingClipboardReadPrompt { _, decide ->
                            decisions += decide
                            AutoCloseable {
                                assertTrue(SwingUtilities.isEventDispatchThread())
                                closes++
                            }
                        }
                    try {
                        val first = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { prompt.request("First") }
                        when (action) {
                            "dismiss" -> assertTrue(prompt.dismiss())
                            "close" -> prompt.close()
                            "allow" -> decisions.first()(SwingClipboardReadPrompt.Decision.ALLOW_ONCE.ordinal)
                            "deny" -> decisions.first()(SwingClipboardReadPrompt.Decision.DENY.ordinal)
                            "block" -> decisions.first()(SwingClipboardReadPrompt.Decision.BLOCK.ordinal)
                        }
                        assertEquals(1, closes, "Disposal must not wait for the caller's dispatcher")
                        assertFalse(prompt.dismiss())
                        val second = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { prompt.request("Second") }
                        if (action == "close" || action == "block") {
                            assertFalse(second.await())
                            assertEquals(1, decisions.size)
                        } else {
                            assertEquals(2, decisions.size)
                            decisions.first()(SwingClipboardReadPrompt.Decision.BLOCK.ordinal)
                            assertFalse(prompt.isBlocked)
                        }
                        dispatcher.scheduler.runCurrent()
                        if (action == "close") {
                            assertFailsWith<CancellationException> { first.await() }
                        } else {
                            assertEquals(action == "allow", first.await())
                        }
                        if (decisions.size == 2) {
                            assertFalse(second.isCompleted, "Old request cleanup must not clear the replacement")
                            assertTrue(prompt.dismiss())
                            assertEquals(2, closes)
                            dispatcher.scheduler.runCurrent()
                            assertFalse(second.await())
                        }
                    } finally {
                        prompt.close()
                        dispatcher.scheduler.runCurrent()
                    }
                }
            }
        }

    @Test
    fun `background cancellation of an unconfined caller disposes on the EDT`() =
        runBlocking {
            val disposed = CompletableDeferred<Unit>()
            var closes = 0
            val prompt =
                SwingClipboardReadPrompt { _, _ ->
                    AutoCloseable {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        closes++
                        disposed.complete(Unit)
                    }
                }
            try {
                val request =
                    withContext(Dispatchers.Swing) {
                        async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { prompt.request("Consent") }
                            .also { pending ->
                                // Cancellation originates outside Swing even though request entry is on the EDT.
                                withContext(Dispatchers.Default) { pending.cancel() }
                            }
                    }
                withTimeout(10_000) {
                    disposed.await()
                    request.join()
                }
                withContext(Dispatchers.Swing) {
                    assertEquals(1, closes)
                    assertFalse(prompt.dismiss())
                    prompt.close()
                    assertEquals(1, closes)
                }
            } finally {
                withContext(Dispatchers.Swing) { prompt.close() }
            }
        }

    @Test
    fun localizedLabelsPreserveSafeDefaultAndPersistentBlockDecision() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val words =
                    SwingHostMessages { key, _ ->
                        when (key) {
                            "clipboard.title" -> "Zustimmung"
                            "clipboard.allowOnce" -> "Einmal erlauben"
                            "clipboard.deny" -> "Ablehnen"
                            "clipboard.block" -> "Für dieses Terminal sperren"
                            else -> error("Unexpected message: $key")
                        }
                    }
                var request: SwingDialogRequest? = null
                var decide: ((Int?) -> Unit)? = null
                var closed = false
                val prompt =
                    SwingClipboardReadPrompt(words) { dialog, decision ->
                        request = dialog
                        decide = decision
                        AutoCloseable { closed = true }
                    }
                try {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { prompt.request("Nachricht") }
                    assertEquals("Zustimmung", request?.title)
                    assertEquals(listOf("Einmal erlauben", "Ablehnen", "Für dieses Terminal sperren"), request?.options)
                    assertEquals(SwingClipboardReadPrompt.Decision.DENY.ordinal, request?.defaultOption)
                    requireNotNull(decide)(SwingClipboardReadPrompt.Decision.BLOCK.ordinal)
                    assertFalse(pending.await())
                    assertTrue(closed)
                    assertTrue(prompt.isBlocked)
                    assertFalse(prompt.request("Erneut"))
                } finally {
                    prompt.close()
                }
            }
        }

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
