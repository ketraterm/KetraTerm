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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.NotificationLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class HostOscEncodingTest {
    @Test
    fun `malformed links clear active context without changing existing cell destinations`() {
        for (prefix in listOf("8;;https://bad/", "8;id=")) {
            val stream =
                ("\u001B]8;id=old;https://old/\u0007A\u001B]$prefix").encodeToByteArray() +
                    byteArrayOf(0xC3.toByte()) + "\u001B\\B\u001B]8;id=new;https://new/�\u0007C".encodeToByteArray()
            for (split in 0..stream.size) {
                val f = Fixture()
                f.parser.accept(stream, 0, split)
                f.parser.accept(stream, split, stream.size - split)
                f.parser.endOfInput()
                val old = checkNotNull(f.terminal.getAttrAt(0, 0)).hyperlinkId
                val next = checkNotNull(f.terminal.getAttrAt(2, 0)).hyperlinkId
                assertTrue(old > 0)
                assertEquals(0, checkNotNull(f.terminal.getAttrAt(1, 0)).hyperlinkId)
                assertTrue(next > old)
                assertEquals(listOf("https://old/", "https://new/�"), f.links)
                assertEquals("ABC", f.terminal.getLineAsString(0))
            }
        }
    }

    @Test
    fun `structured rejection preserves directory palette and response state`() {
        val f = Fixture()
        f.accept("\u001B]7;file:///original\u0007")
        val originalPalette = f.terminal.palette
        for (prefix in listOf("7;file:///bad/", "4;1;#123456;2;", "10;#123456;", "133;A;")) {
            f.parser.accept(("\u001B]$prefix").encodeToByteArray() + byteArrayOf(0xFF.toByte()) + byteArrayOf(7))
        }
        assertEquals(listOf("file:///original"), f.directories)
        assertSame(originalPalette, f.terminal.palette)
        assertEquals(0, f.terminal.readResponseBytes(ByteArray(64)))
        f.accept("\u001B]7;file:///valid/é�\u0007")
        assertEquals(listOf("file:///original", "file:///valid/é�"), f.directories)
    }

    @Test
    fun `malformed clipboard UTF8 is denied before every write permission and recovers`() {
        val invalid =
            listOf(
                byteArrayOf(0x80.toByte()),
                byteArrayOf(0xC3.toByte()),
                byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            )
        for (origin in TerminalClipboardOrigin.entries) {
            for (permission in TerminalClipboardPermission.entries) {
                for (bytes in invalid) {
                    val policy =
                        TerminalClipboardPolicy(
                            origin = origin,
                            localWritePermission = permission,
                            remoteWritePermission = permission,
                            allowlisted = true,
                        )
                    val f = Fixture(HostPolicy(clipboardPolicy = policy))
                    val encoded = Base64.getEncoder().encodeToString(bytes)
                    val stream = "\u001B]52;c;$encoded\u001B\\".encodeToByteArray()
                    for (byte in stream) f.parser.accept(byteArrayOf(byte))
                    assertEquals(TerminalClipboardDecision.DENIED_MALFORMED_PAYLOAD, f.audits.single().decision)
                    assertEquals(bytes.size, f.audits.single().decodedBytes)
                    assertTrue(f.writes.isEmpty())
                    assertTrue(f.prompts.isEmpty())

                    f.accept("\u001B]52;c;" + Base64.getEncoder().encodeToString("é🙂�".encodeToByteArray()) + "\u0007")
                    when (permission) {
                        TerminalClipboardPermission.DENY -> {
                            assertEquals(TerminalClipboardDecision.DENIED_BY_POLICY, f.audits.last().decision)
                        }
                        TerminalClipboardPermission.PROMPT -> assertEquals(listOf("é🙂�"), f.prompts)
                        else -> assertEquals(listOf("é🙂�"), f.writes)
                    }
                }
            }
        }
    }

    @Test
    fun `clipboard bounds precede decoding and malformed envelopes emit no request`() {
        val policy =
            TerminalClipboardPolicy(
                origin = TerminalClipboardOrigin.LOCAL,
                localWritePermission = TerminalClipboardPermission.ALLOW,
                maxDecodedBytes = 1,
            )
        val f = Fixture(HostPolicy(clipboardPolicy = policy))
        f.accept("\u001B]52;c;w8M=\u0007")
        assertEquals(TerminalClipboardDecision.DENIED_PAYLOAD_TOO_LARGE, f.audits.single().decision)
        f.parser.accept("\u001B]52;".encodeToByteArray() + byteArrayOf(0xFF.toByte()) + ";QQ==\u0007".encodeToByteArray())
        assertEquals(1, f.audits.size)
        assertTrue(f.writes.isEmpty())
        f.accept("\u001B]52;c;\u0007")
        assertEquals(listOf(""), f.writes)
    }

    @Test
    fun `display replacement respects permissions and scalar safe length limits`() {
        val expected = listOf("", "A", "A", "A🙂", "A🙂B")
        for (limit in 0..4) {
            val policy =
                HostPolicy(
                    titlePolicy = TerminalTitlePolicy(maxLength = limit),
                    maxNotificationTitleLength = limit,
                    maxNotificationBodyLength = limit,
                )
            val f = Fixture(policy)
            f.accept("\u001B]0;A🙂B\u0007\u001B]777;notify;A🙂B;A🙂B\u0007")
            assertEquals(expected[limit], f.terminal.windowTitle)
            assertEquals(expected[limit], f.terminal.iconTitle)
            assertEquals(listOf(expected[limit] to expected[limit]), f.notifications)
        }
        val f = Fixture()
        f.parser.accept("\u001B]2;bad".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 7))
        assertEquals("bad�", f.terminal.windowTitle)
        f.sink.setHostPolicy(
            HostPolicy(
                titlePolicy = TerminalTitlePolicy(localPermission = TerminalTitlePermission.DENY),
                notificationPolicy = HostControlPolicy.DENY,
            ),
        )
        f.parser.accept("\u001B]2;new".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 7))
        f.accept("\u001B]9;ignored\u0007")
        assertEquals("bad�", f.terminal.windowTitle)
        assertTrue(f.notifications.isEmpty())
    }

    private class Fixture(
        policy: HostPolicy = HostPolicy(),
    ) {
        val terminal = TerminalBuffers.create(10, 3)
        val links = mutableListOf<String>()
        val directories = mutableListOf<String>()
        val audits = mutableListOf<TerminalClipboardAuditEvent>()
        val writes = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val notifications = mutableListOf<Pair<String, String>>()
        val sink =
            HostCommandAdapter(
                terminal,
                object : HostEventSink by HostEventSink.NONE {
                    override fun hyperlinkRegistered(
                        hyperlinkId: Int,
                        uri: String,
                        id: String?,
                    ) {
                        links += uri
                    }

                    override fun currentWorkingDirectoryChanged(uri: String) {
                        directories += uri
                    }

                    override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
                        audits += event
                    }

                    override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
                        writes += event.text
                    }

                    override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
                        prompts += event.text
                    }

                    override fun showNotification(
                        title: String,
                        body: String,
                        level: NotificationLevel,
                    ) {
                        notifications += title to body
                    }
                },
                policy,
            )
        val parser = TerminalParsers.create(sink)

        fun accept(text: String) {
            parser.accept(text.encodeToByteArray())
        }
    }
}
