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
package io.github.ketraterm.ui.swing.settings

import kotlinx.coroutines.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SystemHostServicesTest {
    @ParameterizedTest
    @ValueSource(strings = ["read", "primary", "write", "browser"])
    fun `native operations leave EDT responsive and retain serialization until actual return`(operation: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val edt =
            object : CoroutineDispatcher() {
                override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()

                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) = SwingUtilities.invokeLater(block)
            }
        val scope = CoroutineScope(SupervisorJob() + edt)

        fun nativeCall() {
            assertFalse(SwingUtilities.isEventDispatchThread())
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val clipboard =
            object : Clipboard("test") {
                override fun getContents(requestor: Any?): Transferable {
                    nativeCall()
                    return StringSelection("λ 🚀")
                }

                override fun setContents(
                    contents: Transferable,
                    owner: ClipboardOwner?,
                ) = nativeCall()
            }
        val handler = SystemTerminalClipboardHandler({ clipboard }, { clipboard })
        val browser =
            SystemTerminalHyperlinkHandler(browse = {
                nativeCall()
                true
            })

        suspend fun invoke(): Any? =
            when (operation) {
                "read" -> handler.readTextAsync()
                "primary" -> handler.readPrimarySelectionTextAsync()
                "write" -> handler.copyTextAsync("λ 🚀")
                else -> browser.openHyperlinkAsync("https://example.test/")
            }
        try {
            val first = onEdt { scope.async { invoke() } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second =
                onEdt {
                    first.cancel()
                    val obsolete = scope.async(start = CoroutineStart.UNDISPATCHED) { invoke() }
                    obsolete.cancel()
                    // UNDISTPATCHED reaches the occupied mutex before this EDT turn completes.
                    scope.async(start = CoroutineStart.UNDISPATCHED) { invoke() }.also {
                        assertEquals(1, calls.get())
                        assertFalse(it.isCompleted)
                    }
                }
            release.countDown()
            runBlocking {
                withTimeout(10.seconds) {
                    first.join()
                    assertTrue(first.isCancelled)
                    val result = second.await()
                    if (operation == "read" || operation == "primary") assertEquals("λ 🚀", result)
                    if (operation == "browser") assertEquals(true, result)
                }
            }
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://bad host/", "file:///tmp/test", "javascript:alert(1)", "relative/path"])
    fun `asynchronous browser retains URI policy before OS access`(uri: String) =
        runBlocking {
            val handler = SystemTerminalHyperlinkHandler(browse = { error("Unexpected browser request") })
            assertFalse(handler.openHyperlinkAsync(uri))
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
