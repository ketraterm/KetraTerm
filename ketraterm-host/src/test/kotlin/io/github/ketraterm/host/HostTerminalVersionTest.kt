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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostTerminalVersionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `version replies obey only terminal-response permission across every split`(denied: Boolean) {
        val wire = "\u001b[3;4H\u001b[1;3m\u001b[>q\u001b[>0q\u001b[6nX".encodeToByteArray()
        val version = "\u001bP>|" + TerminalCapabilityIdentity.XTVERSION_TEXT + "\u001b\\"
        for (split in -1..wire.size) {
            val buffer = TerminalBuffers.create(10, 5)
            val parser =
                TerminalParsers.create(
                    HostCommandAdapter(
                        buffer,
                        hostPolicy =
                            HostPolicy(
                                terminalResponsePolicy = if (denied) HostControlPolicy.DENY else HostControlPolicy.ALLOW,
                                windowManipulationPolicy = HostControlPolicy.DENY,
                                palettePolicy = HostControlPolicy.DENY,
                            ),
                    ),
                )
            if (split < 0) {
                wire.forEach { parser.acceptByte(it.toInt() and 255) }
            } else {
                parser.accept(wire, 0, split)
                parser.accept(wire, split, wire.size - split)
            }
            parser.endOfInput()
            assertEquals(if (denied) "" else version + version + "\u001b[3;4R", drain(buffer), "split=$split")
            assertEquals('X'.code, buffer.getCodepointAt(3, 2))
            assertEquals(4, buffer.cursorCol)
            assertEquals(2, buffer.cursorRow)
            val attr = checkNotNull(buffer.getAttrAt(3, 2))
            assertTrue(attr.bold && attr.italic)
        }
    }

    @Test
    fun `invalid version queries stay silent and do not block later queries`() {
        val buffer = TerminalBuffers.create(10, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        val invalid =
            listOf(
                ">1q",
                ">0;0q",
                ">;q",
                ">0:0q",
                ">999999999999999999999q",
                ">0 q",
                "?0q",
                "=0q",
                ">0\u0018",
                ">0\u001a",
            )
        for (body in invalid) {
            parser.accept(("\u001b[" + body + "\u001b[5n").encodeToByteArray())
            assertEquals("\u001b[0n", drain(buffer), body)
        }
        parser.accept("\u001b[>q".encodeToByteArray())
        assertEquals("\u001bP>|" + TerminalCapabilityIdentity.XTVERSION_TEXT + "\u001b\\", drain(buffer))
    }

    @Test
    fun `retained JVM implementations may ignore the optional query without linkage errors`() {
        assertTrue(TerminalCommandSink::class.java.getMethod("requestTerminalVersion").isDefault)
        val underlying = TerminalBuffers.create(10, 3)
        val type = TerminalBuffer::class.java
        // A retained wrapper has no implementation of the newly added query method.
        val wrapped =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                if (method.name == "requestTerminalVersion") {
                    assertTrue(method.isDefault)
                    InvocationHandler.invokeDefault(proxy, method)
                } else {
                    method.invoke(underlying, *(args ?: emptyArray()))
                }
            } as TerminalBuffer
        val parser = TerminalParsers.create(HostCommandAdapter(wrapped))
        parser.accept("\u001b[>q\u001b[5n".encodeToByteArray())
        assertEquals("\u001b[0n", drain(underlying))
    }

    private fun drain(buffer: TerminalBuffer): String {
        val bytes = ByteArray(buffer.pendingResponseBytes)
        assertEquals(bytes.size, buffer.readResponseBytes(bytes))
        return bytes.decodeToString()
    }
}
