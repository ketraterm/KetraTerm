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
package io.github.ketraterm.testkit

import io.github.ketraterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MockConnectorTest {
    @Test
    fun `repeated start rejects replacement and retains original listener`() {
        val connector = MockConnector()
        val first = RecordingListener()
        val second = RecordingListener()
        connector.start(first)
        assertThrows(IllegalStateException::class.java) { connector.start(second) }
        connector.feedFromHost("final bytes".encodeToByteArray())
        assertEquals(listOf("final bytes"), first.output)
        assertTrue(second.output.isEmpty())
        assertEquals(1, connector.startCount)
        connector.close()
        assertThrows(IllegalStateException::class.java) { connector.start(second) }
    }

    @Test
    fun `close before start prevents listener installation`() {
        val connector = MockConnector()
        connector.close()
        assertThrows(IllegalStateException::class.java) { connector.start(RecordingListener()) }
        assertEquals(0, connector.startCount)
    }

    private class RecordingListener : TerminalConnectorListener {
        val output = mutableListOf<String>()

        override fun onBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            output += bytes.decodeToString(offset, offset + length)
        }

        override fun onClosed(exitCode: Int?) = Unit

        override fun onError(error: Throwable) = throw error
    }
}
