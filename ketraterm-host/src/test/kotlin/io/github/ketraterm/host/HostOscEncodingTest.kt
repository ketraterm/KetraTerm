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
    fun `completed oversized links clear context but preserve destinations already written`() {
        for (terminator in listOf("\u0007", "\u001B\\")) {
            val bytes =
                (
                    "\u001B]8;;https://old/\u0007A\u001B]8;;https://bad/" + "x".repeat(4096) +
                        terminator + "B\u001B]8;;https://new/\u0007C"
                ).encodeToByteArray()
            for (split in listOf(0, 1, 27, 4095, 4096, 4097, bytes.size - 1, bytes.size)) {
                val f = Fixture()
                f.parser.accept(bytes, 0, split)
                f.parser.accept(bytes, split, bytes.size - split)
                f.parser.endOfInput()
                val old = checkNotNull(f.terminal.getAttrAt(0, 0)).hyperlinkId
                val next = checkNotNull(f.terminal.getAttrAt(2, 0)).hyperlinkId
                assertTrue(old > 0)
                assertEquals(0, checkNotNull(f.terminal.getAttrAt(1, 0)).hyperlinkId)
                assertTrue(next > old)
                assertEquals(listOf("https://old/", "https://new/"), f.links)
                assertEquals("ABC", f.terminal.getLineAsString(0))
            }
        }
    }

    @Test
    fun `oversized metadata preserves previous state and produces no partial effects`() {
        val f = Fixture()
        f.accept("\u001B]2;original\u0007\u001B]7;file:///original\u0007")
        val palette = f.terminal.palette
        for ((prefix, size) in listOf(
            "2;" to 4097,
            "7;" to 4097,
            "9;" to 4097,
            "777;notify;t;" to 4097,
            "4;1;#123456;2;?;" to 4097,
            "10;#123456;?;" to 257,
        )) {
            f.accept("\u001B]" + prefix.padEnd(size, 'x') + "\u0007")
        }
        assertEquals("original", f.terminal.windowTitle)
        assertEquals(listOf("file:///original"), f.directories)
        assertSame(palette, f.terminal.palette)
        assertTrue(f.notifications.isEmpty())
        assertEquals(0, f.terminal.readResponseBytes(ByteArray(128)))
        f.accept("\u001B]10;#123456\u0007\u001B]2;recovered\u0007")
        assertNotSame(palette, f.terminal.palette)
        assertEquals("recovered", f.terminal.windowTitle)
    }

    @Test
    fun `clipboard raw envelope ceiling precedes decoded size and permission callbacks`() {
        val f = Fixture(HostPolicy(clipboardPolicy = TerminalClipboardPolicy(remoteWritePermission = TerminalClipboardPermission.ALLOW)))
        val accepted = "a".repeat(3066)
        f.accept("\u001B]52;c;" + Base64.getEncoder().encodeToString(accepted.encodeToByteArray()) + "\u0007")
        assertEquals(listOf(accepted), f.writes)
        assertEquals(1, f.audits.size)
        val tooLarge = Base64.getEncoder().encodeToString("a".repeat(3067).encodeToByteArray())
        for (byte in "\u001B]52;c;$tooLarge\u001B\\".encodeToByteArray()) f.parser.acceptByte(byte.toInt() and 0xff)
        assertEquals(listOf(accepted), f.writes)
        assertEquals(1, f.audits.size)
        assertTrue(f.prompts.isEmpty())
        f.accept("\u001B]52;c;Yg==\u0007")
        assertEquals(listOf(accepted, "b"), f.writes)
    }

    @Test
    fun `DCS overflow emits no prefix response and later queries retain allowlist and permissions`() {
        for (permission in HostControlPolicy.entries) {
            val f = Fixture(HostPolicy(terminalResponsePolicy = permission))
            f.accept("\u001BP\$q" + "x".repeat(63) + "\u001B\\")
            f.accept("\u001BP+q436f;" + "0".repeat(4096) + "\u001B\\")
            assertEquals(0, f.terminal.readResponseBytes(ByteArray(128)))
            f.accept("\u001BP\$qz\u001B\\\u001BP+q436f\u001B\\\u001BP+q5A5A\u001B\\")
            val output = ByteArray(128)
            val count = f.terminal.readResponseBytes(output)
            val expected =
                if (permission == HostControlPolicy.ALLOW) {
                    "\u001BP0\$rz\u001B\\\u001BP1+r436f=323536\u001B\\\u001BP0+r\u001B\\"
                } else {
                    ""
                }
            assertEquals(expected, output.decodeToString(0, count))
        }
    }

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
