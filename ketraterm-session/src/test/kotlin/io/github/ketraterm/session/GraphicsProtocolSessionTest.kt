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
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.zip.DeflaterOutputStream
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GraphicsProtocolSessionTest {
    @Test
    fun `decoded chunked placements use the final chunk cursor and precede following reports`() =
        runTest {
            for (compressed in listOf(false, true)) {
                Fixture(testScheduler).use { f ->
                    val png = pngBytes()
                    val payload = Base64.getEncoder().encodeToString(if (compressed) zlib(png) else png)
                    val controls =
                        "a=T,i=4294967295,p=4294967295,f=100,c=2,r=2" +
                            if (compressed) ",o=z,S=${png.size}" else ""
                    f.feed(apc("$controls,m=1", payload.take(4)))
                    assertTrue(f.placements().isEmpty())
                    assertEquals("", f.replies())
                    f.feed("\u001b[3;4H")
                    f.feed(apc("a=T,m=0", payload.drop(4)) + "\u001b[6n")
                    val placement = f.placements().single()
                    assertEquals(3.0, placement.column)
                    assertEquals(2.0, placement.row)
                    assertEquals(2.0, placement.width)
                    assertEquals(2.0, placement.height)
                    assertPixels(placement.image, 0xffff0000.toInt(), 0x8000ff00.toInt(), 0x000000ff, -1)
                    assertEquals(apc("i=4294967295,p=4294967295", "OK") + "\u001b[4;6R", f.replies())
                }
            }
        }

    @Test
    fun `decoded replacement is atomic and upload-only removes old placements without mutating frozen pixels`() =
        runTest {
            Fixture(testScheduler).use { f ->
                val png = Base64.getEncoder().encodeToString(pngBytes())
                f.feed(apc("a=T,i=7,p=1,f=100,C=1,c=1,r=1", png))
                f.feed("\u001b[3;4H" + apc("a=p,i=7,p=2,C=1,c=1,r=1"))
                val before = f.placements()
                assertEquals(2, before.size)
                assertSame(before[0].image, before[1].image)
                assertEquals(apc("i=7,p=1", "OK") + apc("i=7,p=2", "OK"), f.replies())

                f.feed(apc("a=T,i=7,p=3,f=100", "AAEC") + "\u001b[6n")
                assertEquals(before, f.placements())
                assertEquals(apc("i=7,p=3", "EINVAL:invalid graphics data") + "\u001b[3;4R", f.replies())

                // Completing an upload-only replacement removes all old placements before
                // the later put command sees it, without changing the current cursor.
                val raw = Base64.getEncoder().encodeToString(zlib(byteArrayOf(9, 8, 7, 6)))
                f.feed(apc("a=t,i=7,f=32,o=z,s=1,v=1", raw) + "\u001b[6n")
                assertTrue(f.placements().isEmpty())
                assertEquals(apc("i=7", "OK") + "\u001b[3;4R", f.replies())
                f.feed(apc("a=p,i=7,p=3,C=1,c=1,r=1"))
                val replacement = f.placements().single()
                assertNotSame(before[0].image, replacement.image)
                assertPixels(replacement.image, 0x06090807)
                assertPixels(before[0].image, 0xffff0000.toInt(), 0x8000ff00.toInt(), 0x000000ff, -1)
                assertEquals(apc("i=7,p=3", "OK"), f.replies())
            }
        }

    @Test
    fun `real decoded query replies obey quiet modes and permission before the following status report`() =
        runTest {
            val png = Base64.getEncoder().encodeToString(pngBytes())
            for (policy in listOf(HostControlPolicy.ALLOW, HostControlPolicy.DENY)) {
                for (quiet in 0..2) {
                    Fixture(testScheduler, HostPolicy(terminalResponsePolicy = policy)).use { f ->
                        for ((controls, payload, response) in listOf(
                            Triple("f=100", png, "OK"),
                            Triple("f=100", "AAEC", "EINVAL:invalid graphics data"),
                            Triple("f=100,o=z,S=34603009", "eJwDAAAAAAE=", "EFBIG:graphics limit exceeded"),
                            Triple("f=100,t=s", png, "ENOTSUP:unsupported graphics operation"),
                        )) {
                            f.feed(apc("a=q,i=9,$controls,q=$quiet", payload) + "\u001b[6n")
                            assertTrue(f.placements().isEmpty())
                            val expected =
                                if (policy == HostControlPolicy.DENY) {
                                    ""
                                } else {
                                    (if (quiet == 2 || quiet == 1 && response == "OK") "" else apc("i=9", response)) + "\u001b[1;1R"
                                }
                            assertEquals(expected, f.replies(), "$policy q=$quiet $controls")
                        }
                    }
                }
            }
        }

    private class Fixture(
        private val scheduler: TestCoroutineScheduler,
        policy: HostPolicy = HostPolicy(),
    ) : AutoCloseable {
        private val connector = MockConnector()
        private val dispatcher = StandardTestDispatcher(scheduler)
        private val session =
            TerminalSession.create(
                TerminalBuffers.create(10, 6).apply { resize(10, 6, 0, 80, 96) },
                connector,
                hostPolicy = policy,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )
        private var written = 0

        init {
            session.start(10, 6)
        }

        fun feed(bytes: String) = connector.feedFromHost(bytes.encodeToByteArray())

        fun replies(): String {
            scheduler.runCurrent()
            val bytes = connector.writtenBytes
            return bytes.decodeToString(written, bytes.size).also { written = bytes.size }
        }

        fun placements(): List<Placement> =
            buildList {
                session.readRenderFrame { frame ->
                    frame.copyImages { image, column, row, width, height, _, _, _, _, _, _, _ ->
                        add(Placement(image, column, row, width, height))
                    }
                }
            }

        override fun close() = session.close()
    }

    private data class Placement(
        val image: TerminalRenderImage,
        val column: Double,
        val row: Double,
        val width: Double,
        val height: Double,
    )

    private fun assertPixels(
        image: TerminalRenderImage,
        vararg expected: Int,
    ) {
        val pixels = IntArray(image.width * image.height)
        image.copyArgb(pixels)
        assertContentEquals(expected, pixels)
    }

    private fun pngBytes(): ByteArray {
        // Reuse the pinned sender's PNG; only the test command controls vary.
        val wire = checkNotNull(javaClass.getResourceAsStream("/kitty-png.bin")).use { it.readBytes().decodeToString() }
        return Base64.getDecoder().decode(wire.substringAfter(';').substringBefore("\u001b\\"))
    }

    private fun zlib(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .apply {
                DeflaterOutputStream(this).use { it.write(bytes) }
            }.toByteArray()

    private fun apc(
        controls: String,
        payload: String = "",
    ): String = "\u001b_G$controls;$payload\u001b\\"
}
