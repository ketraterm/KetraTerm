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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.api.TerminalRenderImage
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.*

class TerminalGraphicsReplayTest {
    @Test
    fun `pinned Yazi RGB upload cropped placements and deletion survive fragmented ingress`() {
        for (chunk in listOf(1, 7, 4096, 131072)) {
            val terminal = TerminalBuffers.create(80, 24)
            terminal.resize(80, 24, 0, 640, 384)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))

            fun replay(name: String) {
                val bytes = checkNotNull(javaClass.getResourceAsStream("/kitty-graphics/$name.bin")).use { it.readBytes() }
                var offset = 0
                while (offset < bytes.size) {
                    val length = minOf(chunk, bytes.size - offset)
                    parser.accept(bytes, offset, length)
                    offset += length
                }
            }
            replay("yazi-probes")
            val reply = ByteArray(terminal.pendingResponseBytes)
            terminal.readResponseBytes(reply, 0, reply.size)
            assertTrue(reply.decodeToString().startsWith("\u001b_Gi=278941603;OK\u001b\\\u001b_Gi=916472805;ENOTSUP:"))
            replay("yazi-rgb-upload")
            replay("yazi-placements")
            var resource: TerminalRenderImage? = null
            var count = 0
            (terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
                frame.copyImages { image, _, _, width, height, sx, sy, sw, sh, _, _, _ ->
                    if (resource == null) resource = image else assertSame(resource, image)
                    assertEquals(1.0, width)
                    assertEquals(1.0, height)
                    assertTrue(sx in listOf(0, 24, 48))
                    assertTrue(sy in listOf(0, 9))
                    assertTrue(sw in listOf(24, 25))
                    assertTrue(sh in listOf(9, 10))
                    count++
                }
            }
            assertEquals(6, count, "chunk=$chunk")
            val image = checkNotNull(resource)
            val pixels = IntArray(image.width * image.height)
            image.copyArgb(pixels)
            val rgb = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                rgb[i * 3] = (pixels[i] ushr 16).toByte()
                rgb[i * 3 + 1] = (pixels[i] ushr 8).toByte()
                rgb[i * 3 + 2] = pixels[i].toByte()
            }
            assertEquals(
                "5365a1124bb09d8e19703b3e02d9675a2137a2636cdc10e1b363a943030d61e2",
                MessageDigest.getInstance("SHA-256").digest(rgb).toHexString(),
            )
            replay("yazi-delete-placements")
            terminal.readRenderFrame { it.copyImages { _, _, _, _, _, _, _, _, _, _, _, _ -> fail("Deleted placement was published") } }
            val afterDelete = IntArray(pixels.size)
            image.copyArgb(afterDelete)
            assertContentEquals(pixels, afterDelete)
        }
    }
}
