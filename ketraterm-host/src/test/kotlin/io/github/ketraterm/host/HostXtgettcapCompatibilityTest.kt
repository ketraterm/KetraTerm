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
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostXtgettcapCompatibilityTest {
    @Test
    fun `existing Kotlin query overrides still replace host replies`() {
        val underlying = TerminalBuffers.create(10, 3)
        val intercepted = mutableListOf<String>()
        val wrapped =
            object : TerminalBuffer by underlying {
                override fun queryTerminfo(rawPayload: String) {
                    intercepted += rawPayload
                    // An existing wrapper may filter, rewrite, or replace this query.
                    underlying.queryTerminfo("544e")
                }
            }
        val parser = TerminalParsers.create(HostCommandAdapter(wrapped))
        parser.accept("\u001bP+q436f\u001b\\".encodeToByteArray())
        assertEquals(listOf("436f"), intercepted)
        assertEquals("\u001bP1+r544e=787465726d2d323536636f6c6f72\u001b\\", drain(underlying))
    }

    @Test
    fun `denied queries invoke neither the original method nor the metadata hook`() {
        val underlying = TerminalBuffers.create(10, 3)
        val calls = mutableListOf<String>()
        val wrapped =
            object : TerminalBuffer by underlying {
                override fun queryTerminfo(rawPayload: String) {
                    calls += "query"
                }

                override fun setTerminfoBackspaceDefault(sendsBackspace: Boolean) {
                    calls += "metadata"
                }
            }
        val parser =
            TerminalParsers.create(
                HostCommandAdapter(wrapped, hostPolicy = HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY)),
            )
        parser.accept("\u001bP+q6b62\u001b\\".encodeToByteArray())
        assertEquals(emptyList(), calls)
        assertEquals("", drain(underlying))
    }

    @Test
    fun `wrappers without the new hook retain queries without guessing Backspace defaults`() {
        val underlying = TerminalBuffers.create(10, 3)
        var intercepted = 0
        // Model a retained JVM wrapper with no implementation of the new hook:
        // the interface default runs, while existing calls still reach its delegate.
        val type = TerminalBuffer::class.java
        val wrapped =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                if (method.name == "setTerminfoBackspaceDefault") {
                    assertTrue(method.isDefault)
                    InvocationHandler.invokeDefault(proxy, method, *checkNotNull(args))
                } else {
                    if (method.name == "queryTerminfo") intercepted++
                    method.invoke(underlying, *(args ?: emptyArray()))
                }
            } as TerminalBuffer
        val parser = TerminalParsers.create(HostCommandAdapter(wrapped, defaultBackarrowSendsBackspace = true))
        parser.accept("\u001bP+q6b62;436f\u001b\\".encodeToByteArray())
        assertEquals(1, intercepted)
        assertEquals("\u001bP0+r6b62\u001b\\\u001bP1+r436f=323536\u001b\\", drain(underlying))
        parser.accept("\u001b[?67h\u001bP+q6b62\u001b\\".encodeToByteArray())
        assertEquals(2, intercepted)
        assertEquals("\u001bP1+r6b62=08\u001b\\", drain(underlying))
    }

    private fun drain(buffer: TerminalBuffer): String {
        val bytes = ByteArray(buffer.pendingResponseBytes)
        assertEquals(bytes.size, buffer.readResponseBytes(bytes))
        return bytes.decodeToString()
    }
}
