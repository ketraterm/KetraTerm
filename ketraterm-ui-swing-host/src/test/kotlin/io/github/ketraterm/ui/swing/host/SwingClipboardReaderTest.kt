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
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class SwingClipboardReaderTest {
    @Test
    fun selectionsUseFirstAvailableTextWithoutAliasingPrimaryOrHistoricalTargets() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val clipboard = Clipboard()
                val reader = SwingClipboardReader()
                val dialog = ClipboardDialogFixture()
                val prompt = dialog.prompt

                suspend fun read(selectors: String): TerminalClipboardReadResult {
                    clipboard.calls.clear()
                    return reader.read(request(selectors), prompt, "Read?", clipboard)
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
                val reader = SwingClipboardReader()
                val dialog = ClipboardDialogFixture()
                val prompt = dialog.prompt
                val ask = request(permission = TerminalClipboardPermission.PROMPT)

                suspend fun decide(button: String): TerminalClipboardReadResult =
                    coroutineScope {
                        val result = async(start = CoroutineStart.UNDISPATCHED) { reader.read(ask, prompt, "Read?", clipboard) }
                        assertTrue(dialog.isVisible)
                        assertTrue(clipboard.calls.isEmpty())
                        dialog.click(button)
                        result.await()
                    }
                assertSame(
                    TerminalClipboardReadResult.Denied,
                    reader.read(request(permission = TerminalClipboardPermission.DENY), prompt, "Read?", clipboard),
                )
                assertFalse(dialog.isVisible)
                assertSame(TerminalClipboardReadResult.Denied, decide("Deny"))
                assertEquals("clipboard", assertIs<TerminalClipboardReadResult.Text>(decide("Allow once")).text)
                clipboard.calls.clear()
                assertSame(TerminalClipboardReadResult.Denied, decide("Block for this terminal"))
                assertSame(TerminalClipboardReadResult.Denied, reader.read(request(), prompt, "Read?", clipboard))
                assertTrue(clipboard.calls.isEmpty())
                assertFalse(dialog.isVisible)
                prompt.close()
            }
        }

    @Test
    fun windowRejectsAnotherPromptAndCancellationReleasesPresentation() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val clipboard = Clipboard()
                val reader = SwingClipboardReader()
                val firstDialog = ClipboardDialogFixture()
                val first = firstDialog.prompt
                val secondDialog = ClipboardDialogFixture()
                val second = secondDialog.prompt
                val ask = request(permission = TerminalClipboardPermission.PROMPT)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { reader.read(ask, first, "First terminal", clipboard) }
                assertSame(TerminalClipboardReadResult.Denied, reader.read(ask, second, "Second terminal", clipboard))
                assertFalse(secondDialog.isVisible)
                assertTrue(firstDialog.isVisible)
                pending.cancelAndJoin()
                assertFalse(firstDialog.isVisible)
                firstDialog.click("Allow once")
                assertTrue(clipboard.calls.isEmpty())
                val replacement = async(start = CoroutineStart.UNDISPATCHED) { reader.read(ask, second, "Second terminal", clipboard) }
                assertTrue(secondDialog.isVisible)
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
                val firstPrompt = withContext(Dispatchers.Swing) { SwingClipboardReadPrompt { _, _ -> error("Allow must not prompt") } }
                val secondPrompt = withContext(Dispatchers.Swing) { SwingClipboardReadPrompt { _, _ -> error("Allow must not prompt") } }
                val secondReader = SwingClipboardReader()
                val pending =
                    async {
                        withContext(Dispatchers.Swing) { SwingClipboardReader().read(request(), firstPrompt, "First", blocking) }
                    }
                try {
                    entered.await()
                    pending.cancel()
                    // The contender actually reaches admission while the cancelled native call is held.
                    val busy = withContext(Dispatchers.Swing) { secondReader.read(request(), secondPrompt, "Second", clipboard) }
                    assertSame(TerminalClipboardReadResult.Unavailable, busy)
                    assertTrue(clipboard.calls.isEmpty())
                } finally {
                    release.countDown()
                    pending.cancelAndJoin()
                    withContext(Dispatchers.Swing) { firstPrompt.close() }
                }
                assertFailsWith<CancellationException> { pending.await() }
                val result = withContext(Dispatchers.Swing) { secondReader.read(request(), secondPrompt, "Second", clipboard) }
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
                val dialog = ClipboardDialogFixture()
                val prompt = dialog.prompt
                val caught =
                    assertFailsWith<IllegalStateException> {
                        SwingClipboardReader().read(request(), prompt, "Read?", clipboard)
                    }
                assertEquals(failure.message, caught.message)
                val result = SwingClipboardReader().read(request(), prompt, "Read?", Clipboard())
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
