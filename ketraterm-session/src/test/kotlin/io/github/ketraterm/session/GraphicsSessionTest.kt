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
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GraphicsSessionTest {
    @Test
    fun `ordered graphics replies precede DA and final presentation survives closure`() =
        runTest {
            val terminal = TerminalBuffers.create(10, 6)
            terminal.resize(10, 6, 0, 80, 96)
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = TerminalSession.create(terminal, connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
            session.start(10, 6)
            try {
                connector.feedFromHost(
                    (
                        "\u001b_Ga=q,i=99,f=24,s=1,v=1;/wAA\u001b\\\u001b[c" +
                            "\u001b_Ga=T,i=7,f=24,s=1,v=1,C=1,c=1,r=1;/wAA\u001b\\"
                    ).encodeToByteArray(),
                )
                runCurrent()
                val replies = connector.writtenBytes.decodeToString()
                assertTrue(replies.startsWith("\u001b_Gi=99;OK\u001b\\\u001b[?"))
                assertTrue(replies.endsWith("\u001b_Gi=7;OK\u001b\\"))
                var retained: TerminalRenderImage? = null
                session.readRenderFrame { frame ->
                    frame.copyImages { image, _, _, _, _, _, _, _, _, _, _, _ -> retained = image }
                }
                assertNotNull(retained)
                session.close()
                runCurrent()
                assertTrue(session.state.value is TerminalSessionState.Closed)
                session.readPublishedFrame { cache ->
                    assertEquals(1, cache.images.size)
                    assertSame(retained, cache.images.image(0))
                }
                val pixels = IntArray(1)
                retained.copyArgb(pixels)
                assertEquals(0xffff0000.toInt(), pixels[0])
            } finally {
                session.close()
                runCurrent()
            }
        }
}
