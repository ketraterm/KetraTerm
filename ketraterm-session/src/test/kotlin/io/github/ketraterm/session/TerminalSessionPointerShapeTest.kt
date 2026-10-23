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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.protocol.TerminalPointerShape
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionPointerShapeTest {
    @Test
    fun `metadata-only pointer output publishes with window actions denied and preserves grid`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(8, 2),
                    connector,
                    hostPolicy = HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                )
            session.use {
                session.start(8, 2)
                session.requestRender(0)
                runCurrent()
                assertNull(session.pointerShape)
                val initial = session.renderGeneration.value
                connector.feedFromHost("\u001B]22;wait\u0007".encodeToByteArray())
                assertEquals(TerminalPointerShape.WAIT, session.pointerShape)
                // Advance the ordinary output publication throttle without requesting a frame.
                testScheduler.advanceTimeBy(20)
                runCurrent()
                assertTrue(session.renderGeneration.value > initial)
                session.setHostPolicy(HostPolicy())
                assertEquals(TerminalPointerShape.WAIT, session.pointerShape)
                session.setHostPolicy(HostPolicy(windowManipulationPolicy = HostControlPolicy.DENY))
                assertEquals(TerminalPointerShape.WAIT, session.pointerShape)
                connector.feedFromHost("\u001B]22;text\u0007".encodeToByteArray())
                assertEquals(TerminalPointerShape.TEXT, session.pointerShape)
                connector.feedFromHost("\u001B]22;\u0007".encodeToByteArray())
                assertNull(session.pointerShape)
                connector.feedFromHost("\u001B]22;wait\u0007".encodeToByteArray())
                assertEquals(TerminalPointerShape.WAIT, session.pointerShape)
                session.readRenderFrame { frame ->
                    assertEquals(0, frame.cursor.column)
                    assertEquals(0, frame.cursor.row)
                }
            }
            assertNull(session.pointerShape)
        }
}
