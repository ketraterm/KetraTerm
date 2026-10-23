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
import io.github.ketraterm.parser.api.TerminalGraphicsOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.TerminalGraphicsResult
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.api.TerminalRenderImage
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.*

class GraphicsCommandTest {
    @Test
    fun `decoded command handoff preserves response policy and suffix ordering`() {
        for (policy in listOf(HostControlPolicy.ALLOW, HostControlPolicy.DENY)) {
            val terminal = TerminalBuffers.create(10, 6)
            terminal.resize(10, 6, 0, 80, 96)
            val adapter = HostCommandAdapter(terminal, hostPolicy = HostPolicy(terminalResponsePolicy = policy))
            val parser = TerminalParsers.create(adapter) as TerminalGraphicsOutputParser
            val bytes = (apc("a=T,f=100,i=7,C=1", "AAEC") + "\u001b[cX").encodeToByteArray()
            val consumed = parser.acceptUntilGraphics(bytes, 0, bytes.size)
            assertEquals(0, terminal.pendingResponseBytes)
            var pixelCopies = 0
            val decoded =
                object : TerminalRenderImage {
                    override val width = 1
                    override val height = 1

                    override fun copyArgb(
                        destination: IntArray,
                        offset: Int,
                    ) {
                        pixelCopies++
                        destination[offset] = 0xffff0000.toInt()
                    }
                }
            // The host consumes an already validated immutable image; codec validation belongs to session.
            parser.consumePendingGraphics { command, payload, length ->
                assertEquals(100L, command.value('f'))
                assertContentEquals(byteArrayOf(0, 1, 2), payload.copyOf(length))
                adapter.applyDecodedGraphicsCommand(command, decoded, TerminalGraphicsResult.OK)
            }
            assertEquals(bytes.size - consumed, parser.acceptUntilGraphics(bytes, consumed, bytes.size - consumed))
            val response = ByteArray(terminal.pendingResponseBytes)
            terminal.readResponseBytes(response, 0, response.size)
            if (policy == HostControlPolicy.ALLOW) {
                assertTrue(response.decodeToString().startsWith(apc("i=7", "OK") + "\u001b[?"))
            } else {
                assertEquals(0, response.size)
            }
            assertEquals(0, pixelCopies, "Admission must retain decoded pixels without copying")
            terminal.readRenderFrame { frame ->
                var count = 0
                frame.copyImages { image, _, _, _, _, _, _, _, _, _, _, _ ->
                    count++
                    assertTrue(image.isRetainedBySource)
                    val pixel = IntArray(1)
                    image.copyArgb(pixel)
                    assertEquals(0xffff0000.toInt(), pixel[0])
                }
                assertEquals(1, count)
            }
            assertEquals('X'.code, terminal.getCodepointAt(0, 0))
        }
    }

    @Test
    fun `pixel resize recomputes native image span without moving its cell anchor`() {
        val f = Fixture()
        f.feed("\u001b[2;3H")
        f.command("a=T,i=7,f=24,s=16,v=16,C=1", Base64.getEncoder().encodeToString(ByteArray(16 * 16 * 3)))
        val before = f.placements().single()
        assertEquals(2.0, before.width)
        assertEquals(1.0, before.height)
        f.terminal.resize(10, 6, 0, 160, 192)
        val after = f.placements().single()
        assertSame(before.image, after.image)
        assertEquals(1.0, after.width)
        assertEquals(0.5, after.height)
        assertEquals(before.x, after.x)
        assertEquals(before.y, after.y)
    }

    @Test
    fun `unsupported formats and signed relative controls fail without changing the image`() {
        val f = Fixture()
        f.upload(action = 'T', more = ",C=1,c=1,r=1")
        val old = f.placements().single().image
        f.reply()
        for (controls in listOf("f=100", "o=z", "t=f", "U=1", "P=9,H=-1,V=-2147483648", "a=f")) {
            f.command("i=7,s=1,v=1,$controls", "/wAA")
            assertEquals(apc("i=7", "ENOTSUP:unsupported graphics operation"), f.reply(), controls)
            assertSame(old, f.placements().single().image)
        }
        f.command("i=7,s=4294967295,v=4294967295", "")
        assertEquals(apc("i=7", "EFBIG:graphics limit exceeded"), f.reply())
        assertSame(old, f.placements().single().image)
    }

    @Test
    fun `missing pixel geometry rejects placement but permits upload and queries`() {
        val terminal = TerminalBuffers.create(10, 6)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept(
            (
                apc("i=7,f=24,s=1,v=1", "/wAA") + apc("a=p,i=7") +
                    apc("a=q,i=9,f=24,s=1,v=1", "/wAA")
            ).encodeToByteArray(),
        )
        val reply = ByteArray(terminal.pendingResponseBytes)
        terminal.readResponseBytes(reply, 0, reply.size)
        assertEquals(apc("i=7", "OK") + apc("i=7", "EINVAL:invalid graphics data") + apc("i=9", "OK"), reply.decodeToString())
        assertEquals(0, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
    }

    @Test
    fun `deletion selectors match cells z ranges numbers and cursor without deleting other images`() {
        for (selector in listOf("d=p,x=3,y=2", "d=q,x=3,y=2,z=-1", "d=c", "d=x,x=3", "d=y,y=2", "d=z,z=-1", "d=r,x=7,y=7", "d=i,i=7")) {
            val f = Fixture()
            f.feed("\u001b[2;3H")
            f.upload(action = 'T', more = ",C=1,c=1,r=1,z=-1")
            f.feed("\u001b[4;5H")
            f.upload(id = 8, action = 'T', more = ",C=1,c=1,r=1,z=1")
            val other = f.placements().last().image
            f.feed("\u001b[2;3H")
            f.command("a=d,$selector")
            assertSame(other, f.placements().single().image, selector)
            f.command("a=p,i=7,C=1")
            assertEquals(2, f.placements().size, selector)
        }
        val f = Fixture()
        repeat(2) { f.command("I=9,f=24,s=1,v=1", "/wAA") }
        f.command("a=d,d=N,I=9")
        f.command("a=p,I=9,C=1")
        assertEquals(1, f.placements().size)
        assertTrue(f.reply().endsWith(apc("i=1,I=9", "OK")))
    }

    private class Fixture(
        width: Int = 10,
        height: Int = 6,
        history: Int = 20,
        policy: HostPolicy = HostPolicy(),
    ) {
        val terminal = TerminalBuffers.create(width, height, history)
        val adapter = HostCommandAdapter(terminal, hostPolicy = policy)
        val parser = TerminalParsers.create(adapter)

        init {
            terminal.resize(width, height, 0, width * 8, height * 16)
        }

        fun feed(text: String) = parser.accept(text.encodeToByteArray())

        fun command(
            controls: String,
            payload: String = "",
        ) = feed(apc(controls, payload))

        fun upload(
            id: Long = 7,
            action: Char = 't',
            more: String = "",
            payload: String = "/wAA",
            format: Int = 24,
        ) = command("a=$action,i=$id,f=$format,s=1,v=1$more", payload)

        fun reply(): String {
            val bytes = ByteArray(terminal.pendingResponseBytes)
            terminal.readResponseBytes(bytes, 0, bytes.size)
            return bytes.decodeToString()
        }

        fun placements(offset: Int = 0): List<Placement> {
            val result = mutableListOf<Placement>()
            (terminal as TerminalRenderFrameReader).readRenderFrame(offset) { frame ->
                frame.copyImages { image, column, row, width, height, sx, sy, sw, sh, top, bottom, z ->
                    result += Placement(image, column, row, width, height, sx, sy, sw, sh, top, bottom, z)
                }
            }
            return result
        }
    }

    private data class Placement(
        val image: TerminalRenderImage,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val sx: Int,
        val sy: Int,
        val sw: Int,
        val sh: Int,
        val top: Double,
        val bottom: Double,
        val z: Int,
    )

    @Test fun `raw RGB and RGBA preserve pixels and upload does not place`() {
        val f = Fixture()
        f.upload()
        assertEquals(apc("i=7", "OK"), f.reply())
        assertTrue(f.placements().isEmpty())
        f.command("a=p,i=7,C=1,c=2,r=1")
        val original = f.placements().single().image
        val pixels = IntArray(1)
        original.copyArgb(pixels)
        assertEquals(0xffff0000.toInt(), pixels[0])
        f.upload(action = 'T', more = ",C=1,c=1,r=1", payload = "AP8AgA==", format = 32)
        val replacement = f.placements().single().image
        assertNotSame(original, replacement)
        replacement.copyArgb(pixels)
        assertEquals(0x8000ff00.toInt(), pixels[0])
        original.copyArgb(pixels)
        assertEquals(0xffff0000.toInt(), pixels[0])
    }

    @Test fun `probe validates data and unsupported media before DA1 and respects reply policy`() {
        for (allowed in listOf(true, false)) {
            val policy = if (allowed) HostControlPolicy.ALLOW else HostControlPolicy.DENY
            val f = Fixture(policy = HostPolicy(terminalResponsePolicy = policy))
            f.command("a=q,i=7,f=24,s=1,v=1", "/wAA")
            f.command("a=q,i=8,f=24,s=1,v=1,t=s", "YWJj")
            f.feed("\u001b[c")
            val reply = f.reply()
            if (allowed) {
                assertTrue(reply.startsWith(apc("i=7", "OK") + apc("i=8", "ENOTSUP:unsupported graphics operation")))
                assertTrue(reply.endsWith("c"))
            } else {
                assertEquals("", reply)
            }
            assertTrue(f.placements().isEmpty())
        }
    }

    @Test fun `quiet and anonymous query rules do not manufacture acknowledgments`() {
        val f = Fixture()
        f.upload(more = ",q=1")
        assertEquals("", f.reply())
        f.upload(more = ",q=1", payload = "AA")
        assertEquals(apc("i=7", "EINVAL:invalid graphics data"), f.reply())
        f.upload(more = ",q=2", payload = "AA")
        assertEquals("", f.reply())
        f.command("a=q,I=9,f=24,s=1,v=1", "/wAA")
        f.command("a=q,f=24,s=1,v=1", "/wAA")
        f.command("a=d,d=A")
        assertEquals("", f.reply())
    }

    @Test fun `chunked uploads preserve metadata at every byte boundary`() {
        val stream = apc("a=T,i=4294967295,p=4294967295,f=32,s=1,v=1,C=1,c=1,r=1,m=1", "AP8A") + apc("a=T,m=0", "gA==") + "Z"
        val bytes = stream.encodeToByteArray()
        for (split in 0..bytes.size) {
            val f = Fixture()
            f.parser.accept(bytes, 0, split)
            f.parser.accept(bytes, split, bytes.size - split)
            val pixels = IntArray(1)
            f
                .placements()
                .single()
                .image
                .copyArgb(pixels)
            assertEquals(0x8000ff00.toInt(), pixels[0], "split=$split")
            assertEquals(apc("i=4294967295,p=4294967295", "OK"), f.reply())
            assertEquals('Z'.code, f.terminal.getLineAsString(0)[0].code)
        }
    }

    @Test fun `failed replacement preserves image placement and cursor`() {
        val f = Fixture()
        f.upload(action = 'T', more = ",C=1,c=1,r=1")
        val old = f.placements().single()
        for (payload in listOf("", "!!!!", "A", "AA=", "AA==AAAA", "AAAAAA")) {
            f.upload(action = 'T', payload = payload)
            assertSame(old.image, f.placements().single().image)
            assertEquals(0, f.terminal.cursorCol)
            assertEquals(0, f.terminal.cursorRow)
        }
        f.upload(action = 'T', more = ",x=9")
        assertSame(old.image, f.placements().single().image)
    }

    @Test fun `delete abort reset EOF and screen changes discard incomplete uploads`() {
        for (interruption in listOf("delete", "reset", "soft", "eof", "screen", "cancel", "cancel-sos", "cancel-dcs")) {
            val f = Fixture()
            f.command("a=t,i=7,f=32,s=1,v=1,m=1", "AP8A")
            when (interruption) {
                "delete" -> f.command("a=d,d=A")
                "reset" -> f.feed("\u001bc")
                "soft" -> f.feed("\u001b[!p")
                "eof" -> f.parser.endOfInput()
                "screen" -> f.feed("\u001b[?47h\u001b[?47l")
                "cancel" -> f.feed("\u0018")
                "cancel-sos" -> f.feed("\u001bXignored\u0018")
                "cancel-dcs" -> f.feed("\u001bPignored\u001a")
            }
            f.command("m=0", "gA==")
            f.command("a=p,i=7,C=1")
            assertTrue(f.placements().isEmpty(), interruption)
            assertTrue(f.reply().contains("ENOENT"), interruption)
        }
    }

    @Test fun `external clear reset and screen changes discard partial graphics envelopes`() {
        for (interruption in listOf("clear", "reset", "soft reset", "screen switch")) {
            for (continuation in listOf(false, true)) {
                for (more in 0..1) {
                    val header =
                        if (continuation) {
                            "m=$more"
                        } else {
                            "a=T,i=7,f=24,s=${if (more == 0) 1 else 2},v=1,C=1,m=$more"
                        }
                    val chunk = apc(header, "AP8A")
                    for ((part, split) in listOf("header" to chunk.indexOf(';') - 1, "payload" to chunk.indexOf(';') + 3)) {
                        val context = "$interruption continuation=$continuation more=$more part=$part"
                        val f = Fixture()
                        if (continuation) f.command("a=T,i=7,f=24,s=2,v=1,C=1,m=1", "/wAA")
                        f.feed(chunk.substring(0, split))
                        val epoch = f.terminal.graphicsTransferGeneration
                        when (interruption) {
                            "clear" -> f.terminal.eraseBuffer()
                            "reset" -> f.terminal.reset()
                            "soft reset" -> f.terminal.softReset()
                            "screen switch" -> f.terminal.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
                        }
                        assertNotEquals(epoch, f.terminal.graphicsTransferGeneration, context)
                        f.feed(chunk.substring(split))
                        if (more == 1) f.command("m=0")
                        assertTrue(f.placements().isEmpty(), context)
                        assertEquals("", f.reply(), context)
                        assertEquals("", f.terminal.getLineAsString(0), context)
                        assertEquals(0, f.terminal.cursorCol, context)
                        assertEquals(0, f.terminal.cursorRow, context)

                        f.command("a=p,i=7,C=1")
                        assertEquals(apc("i=7", "ENOENT:image not found"), f.reply(), context)
                        f.upload(id = 8, action = 'T', more = ",C=1,c=1,r=1")
                        assertEquals(apc("i=8", "OK"), f.reply(), context)
                        val pixels = IntArray(1)
                        f
                            .placements()
                            .single()
                            .image
                            .copyArgb(pixels)
                        assertContentEquals(intArrayOf(0xffff0000.toInt()), pixels, context)
                    }
                }
            }
        }
    }

    @Test fun `unknown APC leaves transfer intact while malformed graphics drains`() {
        val f = Fixture()
        f.command("a=T,i=7,f=32,s=1,v=1,m=1,C=1", "AP8A")
        f.feed("\u001b_unknown\u001b\\")
        f.command("m=0", "gA==")
        assertEquals(1, f.placements().size)
        for (header in listOf("i=4294967296", "i=-1", "z=2147483648", "i=1,i=2", "k=7", "i=7,", "a=tt", "i=")) {
            f.feed(apc(header, "LEAK") + "!")
        }
        assertEquals("!!!!!!!!", f.terminal.getLineAsString(0))
    }

    @Test fun `cursor follows effective span including physical wrap and origin margins`() {
        data class Case(
            val x: Int,
            val y: Int,
            val columns: Int,
            val rows: Int,
            val origin: Boolean = false,
            val margins: Boolean = false,
            val expectedX: Int,
            val expectedY: Int,
        )
        val cases =
            listOf(
                Case(2, 1, 1, 1, expectedX = 3, expectedY = 1),
                Case(2, 1, 3, 2, expectedX = 5, expectedY = 2),
                Case(8, 1, 2, 1, expectedX = 0, expectedY = 2),
                Case(8, 1, 25, 1, expectedX = 0, expectedY = 2),
                Case(8, 5, 2, 1, expectedX = 0, expectedY = 5),
                Case(2, 4, 3, 3, expectedX = 5, expectedY = 5),
                Case(8, 3, 2, 1, margins = true, expectedX = 0, expectedY = 4),
                Case(8, 3, 2, 1, origin = true, margins = true, expectedX = 0, expectedY = 3),
                Case(2, 2, 1, 4, origin = true, margins = true, expectedX = 3, expectedY = 5),
            )
        for (c in cases) {
            val f = Fixture()
            f.upload()
            f.feed("\u001b[?7l")
            if (c.margins) f.feed("\u001b[2;4r")
            if (c.origin) f.feed("\u001b[?6h")
            f.feed("\u001b[${c.y + 1 - if (c.origin) 1 else 0};${c.x + 1}H")
            f.command("a=p,i=7,c=${c.columns},r=${c.rows}")
            assertEquals(c.expectedX, f.terminal.cursorCol, c.toString())
            assertEquals(c.expectedY, f.terminal.cursorRow, c.toString())
        }
    }

    @Test fun `pending wrap survives C one and default graphics movement clears it without soft wrap`() {
        val f = Fixture()
        f.upload()
        f.feed("1234567890")
        f.command("a=p,i=7,C=1,c=1,r=1")
        f.feed("X")
        assertEquals(1, f.terminal.cursorRow)
        f.feed("\u001b[1;1H1234567890")
        f.command("a=p,i=7,c=1,r=1")
        assertEquals(0, f.terminal.cursorCol)
        assertEquals(1, f.terminal.cursorRow)
        f.feed("Y")
        assertEquals(1, f.terminal.cursorCol)
    }

    @Test fun `scrollback retains immutable resources and ED2 preserves history`() {
        val f = Fixture()
        f.upload(action = 'T', more = ",C=1,c=1,r=1")
        val image = f.placements().single().image
        f.feed("\u001b[S")
        assertTrue(f.placements().isEmpty())
        assertSame(image, f.placements(1).single().image)
        f.feed("\u001b[2J")
        assertSame(image, f.placements(1).single().image)
        f.feed("\u001b[3J")
        assertTrue(f.placements(1).isEmpty())
    }

    @Test fun `insert and delete lines edit text without moving clipping or deleting ordinary images`() {
        val cases =
            listOf(
                "L" to listOf("A", "", "B", "C", "D", "E"),
                "0L" to listOf("A", "", "B", "C", "D", "E"),
                "999L" to listOf("A", "", "", "", "", ""),
                "M" to listOf("A", "C", "D", "E", "F", ""),
                "0M" to listOf("A", "C", "D", "E", "F", ""),
                "999M" to listOf("A", "", "", "", "", ""),
            )
        for ((command, expectedRows) in cases) {
            val f = Fixture()
            f.feed("\u001b[1;1HA\u001b[2;1HB\u001b[3;1HC\u001b[4;1HD\u001b[5;1HE\u001b[6;1HF")
            f.feed("\u001b[3;3H")
            f.upload(action = 'T', more = ",C=1,c=2,r=2,p=9,X=1,Y=2,z=-1")
            val before = f.placements().single()

            f.feed("\u001b[2;1H\u001b[$command")

            val context = "CSI $command"
            assertEquals(expectedRows, (0 until 6).map(f.terminal::getLineAsString), context)
            val after = f.placements().single()
            assertEquals(before, after, context)
            assertSame(before.image, after.image, context)
            assertEquals(1, f.terminal.cursorRow, context)
            assertEquals(0, f.terminal.cursorCol, context)
            assertEquals(0, f.terminal.historySize, context)
        }
    }

    @Test fun `screen namespaces deletion and replacement are independent`() {
        val f = Fixture()
        f.upload(action = 'T', more = ",C=1,c=1,r=1,p=9")
        val primary = f.placements().single().image
        f.feed("\u001b[?47h")
        f.upload(action = 'T', more = ",C=1,c=1,r=1", payload = "AP8A")
        assertNotSame(primary, f.placements().single().image)
        f.feed("\u001b[?47l")
        assertSame(primary, f.placements().single().image)
        f.command("a=d,d=i,i=7,p=9")
        assertTrue(f.placements().isEmpty())
        f.command("a=p,i=7,C=1")
        assertSame(primary, f.placements().single().image)
        f.command("a=d,d=I,i=7")
        f.command("a=p,i=7,C=1")
        assertTrue(f.placements().isEmpty())
        assertTrue(f.reply().contains("ENOENT"))
    }

    @Test fun `crop offsets scale and image-number lookup preserve placement identity`() {
        val f = Fixture()
        f.command("f=24,s=2,v=2,I=99", Base64.getEncoder().encodeToString(ByteArray(12)))
        assertEquals(apc("i=1,I=99", "OK"), f.reply())
        f.command("a=p,I=99,p=8,C=1,x=1,w=99,h=1,c=2,r=1,X=2,Y=3")
        val p = f.placements().single()
        assertEquals(1, p.sx)
        assertEquals(1, p.sw)
        assertEquals(1, p.sh)
        assertEquals(0.25, p.x)
        assertEquals(3.0 / 16, p.y)
        assertEquals(1.75, p.width)
        f.command("a=p,I=99,p=8,C=1,c=1,r=1")
        assertEquals(1, f.placements().size)
    }

    private companion object {
        fun apc(
            controls: String,
            payload: String = "",
        ) = "\u001b_G$controls;$payload\u001b\\"
    }
}
