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

import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.protocol.TerminalClipboardSelection
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.Container
import java.util.concurrent.CountDownLatch
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class SwingClipboardReaderTest {
    @Test
    fun selectionsUseFirstAvailableTextWithoutAliasingPrimaryOrHistoricalTargets() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val clipboard = Clipboard()
                val reader = SwingClipboardReader(clipboard)
                val prompt = SwingClipboardReadPrompt(JPanel())

                suspend fun read(selectors: String): TerminalClipboardReadResult {
                    clipboard.calls.clear()
                    return reader.read(request(selectors), prompt, "Read?")
                }
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(read("c")).text)
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(read("s")).text)
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(read("")).text)
                assertSame(TerminalClipboardReadResult.Unavailable, read("p"))
                assertEquals(listOf("p"), clipboard.calls)
                assertSame(TerminalClipboardReadResult.Unavailable, read("q01234567"))
                assertTrue(clipboard.calls.isEmpty())
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(read("pc")).text)
                assertEquals(listOf("p", "c"), clipboard.calls)
                clipboard.primary = ""
                assertEquals("", assertIs<TerminalClipboardReadResult.Text>(read("pc")).text)
                assertEquals(listOf("p"), clipboard.calls)
                clipboard.text = null
                assertEquals("", assertIs<TerminalClipboardReadResult.Text>(read("csp")).text)
                assertEquals(listOf("c", "p"), clipboard.calls)
                clipboard.text = "clipboard"
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(read("cp")).text)
                assertEquals(listOf("c"), clipboard.calls)
                prompt.close()
            }
        }

    @Test
    fun consentPrecedesNativeAccessAndBlockPersistsEvenForAllow() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val clipboard = Clipboard()
                val reader = SwingClipboardReader(clipboard)
                val prompt = SwingClipboardReadPrompt(JPanel())
                val ask = request(permission = TerminalClipboardPermission.PROMPT)

                suspend fun decide(button: String): TerminalClipboardReadResult =
                    coroutineScope {
                        val result = async(start = CoroutineStart.UNDISPATCHED) { reader.read(ask, prompt, "Read?") }
                        assertTrue(prompt.component.isVisible)
                        assertTrue(clipboard.calls.isEmpty())
                        prompt.click(button)
                        result.await()
                    }
                assertSame(
                    TerminalClipboardReadResult.Denied,
                    reader.read(request(permission = TerminalClipboardPermission.DENY), prompt, "Read?"),
                )
                assertFalse(prompt.component.isVisible)
                assertSame(TerminalClipboardReadResult.Denied, decide("Deny"))
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(decide("Allow once")).text)
                clipboard.calls.clear()
                assertSame(TerminalClipboardReadResult.Denied, decide("Block for this terminal"))
                assertSame(TerminalClipboardReadResult.Denied, reader.read(request(), prompt, "Read?"))
                assertTrue(clipboard.calls.isEmpty())
                assertFalse(prompt.component.isVisible)
                prompt.close()
            }
        }

    @Test
    fun windowRejectsAnotherPromptAndCancellationReleasesPresentation() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val clipboard = Clipboard()
                val reader = SwingClipboardReader(clipboard)
                val first = SwingClipboardReadPrompt(JPanel())
                val second = SwingClipboardReadPrompt(JPanel())
                val ask = request(permission = TerminalClipboardPermission.PROMPT)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { reader.read(ask, first, "First terminal") }
                assertSame(TerminalClipboardReadResult.Denied, reader.read(ask, second, "Second terminal"))
                assertFalse(second.component.isVisible)
                assertTrue(first.component.isVisible)
                pending.cancelAndJoin()
                assertFalse(first.component.isVisible)
                first.click("Allow once")
                assertTrue(clipboard.calls.isEmpty())
                val replacement = async(start = CoroutineStart.UNDISPATCHED) { reader.read(ask, second, "Second terminal") }
                assertTrue(second.component.isVisible)
                assertTrue(second.dismiss())
                assertSame(TerminalClipboardReadResult.Denied, replacement.await())
                assertFalse(second.dismiss())
                first.close()
                second.close()
            }
        }

    @Test
    fun cancelledNativeCallRetainsGlobalSlotUntilItReallyReturns() =
        runBlocking {
            withTimeout(10_000.milliseconds) {
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val blocking =
                    object : TerminalClipboardHandler {
                        override fun copyText(text: String) = error("Unexpected write")

                        override fun readText(): String {
                            assertFalse(SwingUtilities.isEventDispatchThread())
                            entered.complete(Unit)
                            release.await()
                            return "late"
                        }
                    }
                val clipboard = Clipboard()
                val firstPrompt = withContext(Dispatchers.Swing) { SwingClipboardReadPrompt(JPanel()) }
                val secondPrompt = withContext(Dispatchers.Swing) { SwingClipboardReadPrompt(JPanel()) }
                val secondReader = SwingClipboardReader(clipboard)
                val pending =
                    async {
                        withContext(Dispatchers.Swing) { SwingClipboardReader(blocking).read(request(), firstPrompt, "First") }
                    }
                try {
                    entered.await()
                    pending.cancel()
                    // The contender actually reaches admission while the cancelled native call is held.
                    val busy = withContext(Dispatchers.Swing) { secondReader.read(request(), secondPrompt, "Second") }
                    assertSame(TerminalClipboardReadResult.Unavailable, busy)
                    assertTrue(clipboard.calls.isEmpty())
                } finally {
                    release.countDown()
                    pending.cancelAndJoin()
                    withContext(Dispatchers.Swing) { firstPrompt.close() }
                }
                assertFailsWith<CancellationException> { pending.await() }
                val result = withContext(Dispatchers.Swing) { secondReader.read(request(), secondPrompt, "Second") }
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(result).text)
                withContext(Dispatchers.Swing) { secondPrompt.close() }
            }
        }

    @Test
    fun nativeFailurePropagatesAndReleasesSlot() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val failure = IllegalStateException("provider failure")
                val clipboard =
                    object : TerminalClipboardHandler {
                        override fun copyText(text: String) = error("Unexpected write")

                        override fun readText(): String = throw failure
                    }
                val prompt = SwingClipboardReadPrompt(JPanel())
                val caught =
                    assertFailsWith<IllegalStateException> {
                        SwingClipboardReader(clipboard).read(request(), prompt, "Read?")
                    }
                assertEquals(failure.message, caught.message)
                val result = SwingClipboardReader(Clipboard()).read(request(), prompt, "Read?")
                assertIs<TerminalClipboardReadResult.Text>(result)
                prompt.close()
            }
        }

    private class Clipboard : TerminalClipboardHandler {
        var text: String? = "clipboard"
        var primary: String? = null
        val calls = mutableListOf<String>()

        override fun copyText(text: String) {
            this.text = text
        }

        override fun readText(): String? {
            assertFalse(SwingUtilities.isEventDispatchThread())
            calls += "c"
            return text
        }

        override fun readPrimarySelectionText(): String? {
            assertFalse(SwingUtilities.isEventDispatchThread())
            calls += "p"
            return primary
        }
    }

    private fun request(
        selectors: String = "c",
        permission: TerminalClipboardPermission = TerminalClipboardPermission.ALLOW,
    ) = TerminalClipboardReadRequest(requireNotNull(TerminalClipboardSelection.parse(selectors)), permission, 1024)
}

internal fun SwingClipboardReadPrompt.click(text: String) {
    fun find(container: Container): JButton? {
        for (child in container.components) {
            if (child is JButton && child.text == text) return child
            if (child is Container) find(child)?.let { return it }
        }
        return null
    }
    requireNotNull(find(component)).doClick(0)
}
