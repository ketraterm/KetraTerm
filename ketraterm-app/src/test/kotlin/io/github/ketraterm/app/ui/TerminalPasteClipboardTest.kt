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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.SwingDialogRequest
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.*

class TerminalPasteClipboardTest {
    @Test
    fun `ordinary text including tabs pastes without a prompt`() {
        val source = Clipboard("git status\t工程 😀")
        val clipboard = TerminalPasteClipboard(source) { error("Safe text must not prompt") }
        assertEquals(source.text, clipboard.readText())
        source.text = ""
        assertEquals("", clipboard.readText())
        source.text = null
        assertNull(clipboard.readText())
    }

    @Test
    fun `line breaks controls and Unicode line separators require explicit approval`() {
        val unsafe = listOf("\n", "\r", "\r\n", "\u0000", "\u0003", "\u001b", "\u007f", "\u0085", "\u009b", "\u2028", "\u2029")
        for (control in unsafe) {
            val text = "before${control}after"
            for (approved in listOf(false, true)) {
                var prompts = 0
                val clipboard =
                    TerminalPasteClipboard(Clipboard(text)) { request ->
                        prompts++
                        assertEquals(SwingDialogRequest.Severity.WARNING, request.severity)
                        assertEquals(1, request.defaultOption, "Cancel must be the initial choice")
                        assertFalse(request.message.contains(text), "The preview must escape controls")
                        approved
                    }
                assertEquals(if (approved) text else null, clipboard.readText())
                assertEquals(1, prompts)
            }
        }
    }

    @Test
    fun `primary selection is reviewed independently and copy remains unchanged`() {
        val source = Clipboard("ordinary", "primary\ntext")
        var prompts = 0
        val clipboard =
            TerminalPasteClipboard(source) {
                prompts++
                false
            }
        assertNull(clipboard.readPrimarySelectionText())
        assertEquals(0, source.ordinaryReads)
        assertEquals(1, prompts)
        clipboard.copyText("copy\nwithout approval")
        assertEquals("copy\nwithout approval", source.copied)
    }

    @Test
    fun `review preserves approved text even if the clipboard changes during confirmation`() {
        val source = Clipboard("original\ncommand")
        val clipboard =
            TerminalPasteClipboard(source) {
                source.text = "changed"
                true
            }
        assertEquals("original\ncommand", clipboard.readText())
    }

    @Test
    fun `preview is bounded and never truncates a supplementary character`() {
        var request: SwingDialogRequest? = null
        val source = Clipboard("😀".repeat(500) + "\nsecret suffix")
        TerminalPasteClipboard(source) {
            request = it
            false
        }.readText()
        val message = assertNotNull(request).message
        assertTrue(message.contains("😀".repeat(400) + "…"))
        assertFalse(message.contains("secret suffix"))
        assertFalse(message.contains('�'))
        assertTrue(message.length < 1300)

        source.text = "\u001b".repeat(10_000)
        TerminalPasteClipboard(source) {
            request = it
            false
        }.readText()
        assertTrue(assertNotNull(request).message.length < 1300, "Escaping controls must not create an oversized dialog")
    }

    @Test
    fun `reentrant prompts are denied and failures release the prompt guard`() {
        val source = Clipboard("line\n")
        var fail = true
        lateinit var clipboard: TerminalPasteClipboard
        clipboard =
            TerminalPasteClipboard(source) {
                assertNull(clipboard.readText(), "A nested paste must not open another dialog")
                if (fail) error("dialog failed")
                true
            }
        assertFailsWith<IllegalStateException> { clipboard.readText() }
        fail = false
        assertEquals("line\n", clipboard.readText())
        assertEquals(2, source.ordinaryReads)
    }

    @Test
    fun `only an approved paste with a current view binding reaches the session`() {
        for (decision in listOf("approve", "cancel", "dispose")) {
            val received = CompletableFuture<String>()
            val output = ByteArrayOutputStream()
            val connector =
                object : TerminalConnector {
                    override fun start(listener: TerminalConnectorListener) = Unit

                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) = Unit

                    override fun close() = Unit

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        output.write(bytes, offset, length)
                        val text = output.toString(Charsets.UTF_8)
                        if (text.endsWith("barrier")) received.complete(text)
                    }
                }
            val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
            val bound = CompletableFuture<Throwable?>()
            lateinit var terminal: SwingTerminal
            val clipboard =
                TerminalPasteClipboard(Clipboard("unsafe\ntext")) {
                    if (decision == "dispose") terminal.dispose()
                    decision != "cancel"
                }
            try {
                session.start()
                onEdt {
                    terminal = SwingTerminal(hostServices = SwingHostServices.create { it.clipboardHandler = clipboard })
                    terminal.bind(session, bound::complete)
                }
                assertNull(bound.get(10, TimeUnit.SECONDS))
                onEdt { assertEquals(decision == "approve", terminal.pasteClipboardText()) }
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalPasteEvent("barrier")))
                assertEquals(if (decision == "approve") "unsafe\ntextbarrier" else "barrier", received.get(10, TimeUnit.SECONDS))
            } finally {
                onEdt { terminal.dispose() }
                session.close()
            }
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private class Clipboard(
        var text: String?,
        val primary: String? = null,
    ) : TerminalClipboardHandler {
        var ordinaryReads = 0
        var copied: String? = null

        override fun readText(): String? {
            ordinaryReads++
            return text
        }

        override fun readPrimarySelectionText(): String? = primary

        override fun copyText(text: String) {
            copied = text
        }
    }
}
