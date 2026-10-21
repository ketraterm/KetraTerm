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

import com.fasterxml.jackson.databind.json.JsonMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.zip.Inflater
import javax.imageio.ImageIO

/**
 * Verifies the pinned sender corpus independently of KetraTerm's graphics implementation.
 * These tests establish fixture integrity and decoded inputs, not terminal graphics support.
 */
class TerminalGraphicsFixtureTest {
    private val manifest = JsonMapper.builder().build().readTree(resource("manifest.json"))
    private val cases = manifest.path("cases").toList()

    @Test
    fun `corpus pins immutable producer revisions and all required scenarios`() {
        assertEquals(1, manifest.path("schemaVersion").asInt())
        assertEquals(
            setOf(
                "yazi-probes",
                "yazi-rgb-upload",
                "yazi-rgba-upload",
                "yazi-placements",
                "yazi-delete-placements",
                "yazi-delete-image",
                "kitty-png",
                "kitty-zlib-rgb",
                "kitty-large-rgba",
            ),
            cases.map { it.path("name").asText() }.toSet(),
        )
        assertEquals(cases.size, cases.map { it.path("file").asText() }.toSet().size)
        for (producer in manifest.path("producers")) {
            val revision = producer.path("revision").asText()
            assertTrue(revision.matches(Regex("[a-f0-9]{40}")), "Immutable producer revision: $revision")
            assertTrue(manifest.path("sources").any { it.path("url").asText().contains(revision) })
        }
        for (source in manifest.path("sources")) {
            val producer = manifest.path("producers").path(source.path("producer").asText())
            assertTrue(producer.has("revision"))
            assertTrue(source.path("url").asText().contains(producer.path("revision").asText()))
            assertTrue(source.path("sha256").asText().matches(Regex("[a-f0-9]{64}")))
        }
    }

    @TestFactory
    fun `captured bytes and command boundaries match their manifest`(): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.path("name").asText()) {
                val bytes = resource(case.path("file").asText())
                assertEquals(case.path("sha256").asText(), sha256(bytes))
                val commands = commands(bytes)
                val expected = case.path("expected").path("commands")
                assertEquals(expected.size(), commands.size)
                commands.forEachIndexed { index, command ->
                    val controls = expected[index].path("controls").properties().associate { it.key to it.value.asText() }
                    assertEquals(controls, command.controls, "command $index")
                    assertEquals(expected[index].path("payloadBytes").asInt(), command.payload.length, "command $index")
                }
            }
        }

    @TestFactory
    fun `image payloads decode to the independently generated source pixels`(): List<DynamicTest> =
        cases.filter { it.path("expected").has("image") }.map { case ->
            DynamicTest.dynamicTest(case.path("name").asText()) {
                val expected = case.path("expected").path("image")
                val commands = commands(resource(case.path("file").asText()))
                val output = ByteArrayOutputStream()
                for (command in commands) output.write(Base64.getDecoder().decode(command.payload))
                val encodedImage = output.toByteArray()
                val payload = if (commands.first().controls["o"] == "z") inflate(encodedImage) else encodedImage
                val width = expected.path("width").asInt()
                val height = expected.path("height").asInt()
                val pixels = if (expected.path("format").asInt() == 100) pngPixels(payload, width, height) else payload
                val channels = if (expected.path("pixelFormat").asText() == "RGB8") 3 else 4
                assertEquals(width * height * channels, pixels.size)
                assertEquals(expected.path("pixelSha256").asText(), sha256(pixels))
            }
        }

    @Test
    fun `Yazi probes distinguish inline and shared memory before the DA barrier`() {
        val bytes = resource("yazi-probes.bin")
        val commands = commands(bytes)
        assertEquals(2, commands.size)
        assertEquals("q", commands[0].controls["a"])
        assertEquals("24", commands[0].controls["f"])
        assertEquals("d", commands[0].controls["t"])
        assertEquals("278941603", commands[0].controls["i"])
        assertArrayEquals(byteArrayOf(0, 0, 0), Base64.getDecoder().decode(commands[0].payload))
        assertEquals("q", commands[1].controls["a"])
        assertEquals("s", commands[1].controls["t"])
        assertEquals("916472805", commands[1].controls["i"])
        assertEquals("3", commands[1].controls["S"])
        assertTrue(bytes.toString(Charsets.US_ASCII).endsWith("\u001b[0c"))
    }

    @Test
    fun `Kitty sender retains larger chunks unpadded base64 and repeated continuation actions`() {
        val commands = commands(resource("kitty-large-rgba.bin"))
        assertTrue(commands.size > 1)
        assertEquals(128 * 1024, commands.first().payload.length)
        assertEquals("1", commands.first().controls["m"])
        assertNotEquals("1", commands.last().controls["m"])
        assertFalse(commands.last().payload.endsWith("="))
        assertNotEquals(0, commands.last().payload.length % 4)
        for (command in commands) assertEquals(commands.first().controls["a"], command.controls["a"])
    }

    @Test
    fun `Yazi places cropped cells and distinguishes placement deletion from image deletion`() {
        for (file in listOf("yazi-rgb-upload.bin", "yazi-rgba-upload.bin")) {
            val upload = commands(resource(file))
            assertEquals("t", upload.first().controls["a"])
            assertEquals("73", upload.first().controls["i"])
            assertEquals(4096, upload.first().payload.length)
        }
        val placements = commands(resource("yazi-placements.bin"))
        assertEquals(6, placements.size)
        placements.forEachIndexed { index, command ->
            assertEquals("p", command.controls["a"])
            assertEquals("73", command.controls["i"])
            assertEquals((index + 1).toString(), command.controls["p"])
            assertEquals("1", command.controls["c"])
            assertEquals("1", command.controls["r"])
            assertEquals("1", command.controls["C"])
            assertEquals(((index % 3) * 24).toString(), command.controls["x"])
            assertEquals(((index / 3) * 9).toString(), command.controls["y"])
        }
        val deletions = commands(resource("yazi-delete-placements.bin"))
        assertEquals(6, deletions.size)
        deletions.forEachIndexed { index, command ->
            assertEquals("d", command.controls["a"])
            assertEquals("i", command.controls["d"])
            assertEquals((index + 1).toString(), command.controls["p"])
        }
        val deletion = commands(resource("yazi-delete-image.bin")).single()
        assertEquals("d", deletion.controls["a"])
        assertEquals("I", deletion.controls["d"])
        assertEquals("73", deletion.controls["i"])
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/kitty-graphics/$name")) { "Missing fixture $name" }.use { it.readBytes() }

    private fun commands(bytes: ByteArray): List<GraphicsCommand> {
        assertTrue(bytes.all { (it.toInt() and 0xff) < 128 }, "Sender fixtures must contain only ASCII envelope bytes")
        val text = bytes.toString(Charsets.US_ASCII)
        val result = mutableListOf<GraphicsCommand>()
        var offset = 0
        while (true) {
            val start = text.indexOf("\u001b_G", offset)
            if (start < 0) return result
            val end = text.indexOf("\u001b\\", start + 3)
            assertTrue(end >= 0, "Unterminated captured APC at $start")
            val body = text.substring(start + 3, end)
            val separator = body.indexOf(';')
            val header = if (separator < 0) body else body.substring(0, separator)
            val controls = linkedMapOf<String, String>()
            for (field in header.split(',')) {
                val pair = field.split('=', limit = 2)
                assertEquals(2, pair.size, "Invalid field $field")
                assertNull(controls.put(pair[0], pair[1]), "Duplicate field ${pair[0]}")
            }
            result += GraphicsCommand(controls, if (separator < 0) "" else body.substring(separator + 1))
            offset = end + 2
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val output = ByteArrayOutputStream()
            val scratch = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(scratch)
                assertTrue(count > 0, "Truncated or invalid compressed fixture")
                output.write(scratch, 0, count)
                assertTrue(output.size() <= 1024 * 1024, "Fixture exceeds its test-only decode ceiling")
            }
            assertEquals(0, inflater.remaining, "Trailing compressed fixture bytes")
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun pngPixels(
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        val pixels = ByteArray(width * height * 4)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = image.getRGB(x, y)
                pixels[offset++] = (argb ushr 16).toByte()
                pixels[offset++] = (argb ushr 8).toByte()
                pixels[offset++] = argb.toByte()
                pixels[offset++] = (argb ushr 24).toByte()
            }
        }
        return pixels
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private data class GraphicsCommand(
        val controls: Map<String, String>,
        val payload: String,
    )
}
