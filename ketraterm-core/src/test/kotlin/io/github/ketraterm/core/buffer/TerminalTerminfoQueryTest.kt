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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalResponseChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalTerminfoQueryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `terminal reset clears DECBKM without inventing or discarding host metadata`(soft: Boolean) {
        for (hostDefault in listOf(null, false, true)) {
            val buffer = TerminalBuffers.create(10, 3)
            if (hostDefault != null) buffer.setTerminfoBackspaceDefault(hostDefault)
            buffer.setBackarrowKeyMode(true)
            buffer.queryTerminfo("6b62")
            assertEquals(reply(true, "6b62", "08"), drain(buffer))
            if (soft) buffer.softReset() else buffer.reset()
            buffer.queryTerminfo("6b62")
            val expected =
                when (hostDefault) {
                    null -> reply(false, "6b62")
                    false -> reply(true, "6b62", "7f")
                    true -> reply(true, "6b62", "08")
                }
            assertEquals(expected, drain(buffer), "hostDefault=" + hostDefault)
        }
    }

    @Test
    fun `optional metadata hook preserves the existing JVM implementer contract`() {
        val type = TerminalResponseChannel::class.java
        val metadata = type.getMethod("setTerminfoBackspaceDefault", Boolean::class.javaPrimitiveType)
        assertTrue(metadata.isDefault, "Existing JVM implementations must not acquire a new abstract requirement")
        val received = mutableListOf<String>()
        val channel =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when {
                    method == metadata -> InvocationHandler.invokeDefault(proxy, method, *checkNotNull(args))
                    method.name == "queryTerminfo" && method.parameterCount == 1 -> {
                        received += checkNotNull(args)[0] as String
                        null
                    }
                    else -> error("Unexpected call: " + method)
                }
            } as TerminalResponseChannel
        channel.setTerminfoBackspaceDefault(true)
        channel.queryTerminfo("6b62")
        assertEquals(listOf("6b62"), received)
    }

    @Test
    fun `keyboard queries capture cursor and Backspace modes at admission`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.setTerminfoBackspaceDefault(false)
        buffer.queryTerminfo("6b75;6b62")
        buffer.setApplicationCursorKeys(true)
        buffer.setBackarrowKeyMode(true)
        buffer.queryTerminfo("6b63757531;6b6273")
        assertEquals(
            reply(true, "6b75", "1b5b41") + reply(true, "6b62", "7f") +
                reply(true, "6b63757531", "1b4f41") + reply(true, "6b6273", "08"),
            drain(buffer),
        )
    }

    @Test
    fun `Backspace discovery needs a known host default unless DECBKM is explicit`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.queryTerminfo("6b62;436f")
        assertEquals(reply(false, "6b62") + reply(true, "436f", "323536"), drain(buffer))
        buffer.setTerminfoBackspaceDefault(true)
        buffer.queryTerminfo("6b62")
        assertEquals(reply(true, "6b62", "08"), drain(buffer))
        buffer.setTerminfoBackspaceDefault(false)
        buffer.queryTerminfo("6b62")
        assertEquals(reply(true, "6b62", "7f"), drain(buffer))
        buffer.setBackarrowKeyMode(false)
        buffer.setTerminfoBackspaceDefault(true)
        buffer.queryTerminfo("6b62")
        assertEquals(reply(true, "6b62", "7f"), drain(buffer))
    }

    @Test
    fun `mixed queries preserve order duplicates spelling and unknown failures`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.queryTerminfo("7878;436F;524742;436f;636F;5375")
        assertEquals(
            reply(false, "7878") +
                reply(true, "436F", "323536") +
                reply(true, "524742") +
                reply(true, "436f", "323536") +
                reply(false, "636F") +
                reply(true, "5375"),
            drain(buffer),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "4", "zz", " 436f", "436f ", ";436f", "436f;", "436f;;544e",
            "436f;0g", "436f;F", "４３６ｆ", "436f;\u00A0", "436f;00\n",
        ],
    )
    fun `malformed batches fail atomically without dropping earlier responses`(payload: String) {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.queryTerminfo("436f")
        buffer.queryTerminfo(payload)
        assertEquals(reply(true, "436f", "323536") + reply(false, ""), drain(buffer))
    }

    @Test
    fun `only exact allowlisted names can succeed`() {
        val buffer = TerminalBuffers.create(10, 3)
        for (name in listOf(
            "smulx",
            "SetULc",
            "cols",
            "Sync",
            "Ms",
            "query-os-name",
            "KU",
            "kf13",
            "k0",
            "BE",
            "\u0000",
            "\u001B",
            "\u00FF",
        )) {
            val encoded = name.map { "%02x".format(it.code) }.joinToString("")
            buffer.queryTerminfo(encoded)
            assertEquals(reply(false, encoded), drain(buffer), name)
        }
    }

    @Test
    fun `response budget admits complete frames exactly at the boundary`() {
        val buffer = TerminalBuffers.create(10, 3)
        val longName = "00".repeat(32760)
        buffer.queryTerminfo("00;" + longName)
        val expected = reply(false, "00") + reply(false, longName)
        assertEquals(65536, expected.length)
        assertEquals(expected, drain(buffer))

        buffer.queryTerminfo("00;" + longName + "00")
        assertEquals(reply(false, ""), drain(buffer))
        buffer.queryTerminfo("00".repeat(32769))
        assertEquals(reply(false, ""), drain(buffer))
    }

    @Test
    fun `repeated long values cannot amplify a direct query beyond the reply budget`() {
        val buffer = TerminalBuffers.create(10, 3)
        val key = "536574756c63" // Setulc
        buffer.queryTerminfo(key)
        val single = drain(buffer)
        val count = 65536 / single.length
        buffer.queryTerminfo(List(count) { key }.joinToString(";"))
        assertEquals(single.repeat(count), drain(buffer))
        buffer.queryTerminfo(List(count + 1) { key }.joinToString(";"))
        assertEquals(reply(false, ""), drain(buffer))
    }

    private fun reply(
        success: Boolean,
        key: String,
        value: String? = null,
    ): String = "\u001BP" + (if (success) "1" else "0") + "+r" + key + (value?.let { "=" + it } ?: "") + "\u001B\\"

    private fun drain(buffer: TerminalBuffer): String {
        val bytes = ByteArray(buffer.pendingResponseBytes)
        assertEquals(bytes.size, buffer.readResponseBytes(bytes))
        assertEquals(0, buffer.pendingResponseBytes)
        return bytes.decodeToString()
    }
}
