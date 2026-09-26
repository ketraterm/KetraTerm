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
package io.github.ketraterm.session

import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundWriterTest {
    @Test
    fun `wrap growth and rollback retain exactly the committed bytes`() =
        runTest {
            val connector = MockConnector()
            val writer = OutboundWriter(connector, Any())
            backgroundScope.launch(StandardTestDispatcher(testScheduler)) { writer.run() }
            val first = ByteArray(15000) { (it % 127).toByte() }
            writer.submit { writer.append(first, 0, first.size) }
            runCurrent()
            val second = ByteArray(10000) { (it % 93).toByte() }
            writer.submit { writer.append(second, 0, second.size) }
            assertThrows(IllegalArgumentException::class.java) {
                writer.submit {
                    writer.append(ByteArray(20000) { 42 }, 0, 20000)
                    throw IllegalArgumentException("abort after growing wrapped storage")
                }
            }
            val third = byteArrayOf(1, 2, 3)
            writer.submit { writer.append(third, 0, third.size) }
            third.fill(9)
            runCurrent()
            assertArrayEquals(first + second + byteArrayOf(1, 2, 3), connector.writtenBytes)
            writer.close()
        }

    @Test
    fun `exact byte budget is accepted and one extra byte is rejected atomically`() =
        runTest {
            val connector = MockConnector()
            val writer = OutboundWriter(connector, Any())
            val chunk = ByteArray(16384) { 42 }
            writer.submit {
                repeat(OutboundWriter.MAX_QUEUED_BYTES / chunk.size) { writer.append(chunk, 0, chunk.size) }
            }
            assertThrows(OutboundCapacityException::class.java) {
                writer.submit { writer.append(chunk, 0, 1) }
            }
            backgroundScope.launch(StandardTestDispatcher(testScheduler)) { writer.run() }
            runCurrent()
            val actual = connector.writtenBytes
            assertEquals(OutboundWriter.MAX_QUEUED_BYTES, actual.size)
            assertTrue(actual.all { it == 42.toByte() })
            writer.close()
        }
}
