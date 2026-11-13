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
package consumer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.render.api.TerminalRenderImage
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider

internal fun verifyHeadlessDependencies() {
    val output = StringWriter()
    val classpath = System.getProperty("java.class.path")
    val jars = classpath.split(File.pathSeparator).filter { it.endsWith(".jar") }
    check(jars.isNotEmpty())
    val args = listOf("--multi-release", "25", "--recursive", "-s", "--class-path", classpath) + jars
    val jdeps = ToolProvider.findFirst("jdeps").orElseThrow()
    val status = PrintWriter(output).use { jdeps.run(it, it, *args.toTypedArray()) }
    check(status == 0) { "Headless dependency analysis failed:\n$output" }
    check(!output.toString().contains("java.desktop") && !output.toString().contains("not found")) {
        "The published headless runtime must be desktop-independent and fully resolved:\n$output"
    }
}

internal fun verifyHeadlessSession() {
    check(ModuleLayer.boot().findModule("java.desktop").isEmpty)
    check(runCatching { Class.forName("java.awt.image.BufferedImage") }.exceptionOrNull() is ClassNotFoundException)
    val connector = HeadlessConnector()
    // A host can supply cell geometry without creating a display or frontend.
    val buffer = TerminalBuffers.create(8, 2).apply { resize(8, 2, 0, 64, 32) }
    val closedImage =
        TerminalSession
            .create(
                buffer,
                connector,
                workerDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                cleanupDispatcher = Dispatchers.Unconfined,
            ).use { session ->
                session.start(8, 2)
                connector.feed("text")
                session.encodeKey(TerminalKeyEvent.codepoint('k'.code))
                check(connector.takeOutput() == "k")
                // Independent fixed PNG: one unassociated RGBA pixel, 0x78123456.
                val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMQMgmrAAACDQEVqX6lxgAAAABJRU5ErkJggg=="
                connector.feed("\u001b[H\u001b_Ga=T,f=100,i=7,C=1,c=1,r=1;$png\u001b\\\u001b[5n", chunkSize = 1)
                connector.expectOutput("\u001b_Gi=7;OK\u001b\\\u001b[0n")
                var image: TerminalRenderImage? = null
                session.readRenderFrame { it.copyImages { resource, _, _, _, _, _, _, _, _, _, _, _ -> image = resource } }
                val retained = checkNotNull(image)
                val pixels = IntArray(1)
                retained.copyArgb(pixels)
                check(retained.width == 1 && retained.height == 1 && pixels[0] == 0x78123456)
                // Invalid replacement preserves the prior image and recovers before text and reports.
                connector.feed("\u001b_Ga=T,f=100,i=7;AAEC\u001b\\Z\u001b[5n", chunkSize = 7)
                connector.expectOutput("\u001b_Gi=7;EINVAL:invalid graphics data\u001b\\\u001b[0n")
                val codeWords = IntArray(8)
                var currentImage: TerminalRenderImage? = null
                session.readRenderFrame {
                    it.copyTextLine(0, codeWords, flags = IntArray(8))
                    it.copyImages { resource, _, _, _, _, _, _, _, _, _, _, _ -> currentImage = resource }
                }
                check(codeWords.copyOf(4).contentEquals(intArrayOf('Z'.code, 'e'.code, 'x'.code, 't'.code)))
                check(currentImage === retained)
                connector.feed("\u001b_Ga=d,d=I,i=7,q=2;\u001b\\")
                retained.copyArgb(pixels)
                check(pixels[0] == 0x78123456)
                session.readRenderFrame { it.copyImages { _, _, _, _, _, _, _, _, _, _, _, _ -> error("Deleted image remained visible") } }
                retained
            }
    check(connector.closed)
    val pixels = IntArray(1)
    closedImage.copyArgb(pixels)
    check(pixels[0] == 0x78123456)
}

private class HeadlessConnector : TerminalConnector {
    private var listener: TerminalConnectorListener? = null
    private val output = ByteArrayOutputStream()
    var closed = false
        private set

    override fun start(listener: TerminalConnectorListener) {
        check(this.listener == null && !closed)
        this.listener = listener
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        output.write(bytes, offset, length)
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) = Unit

    override fun close() {
        closed = true
    }

    fun feed(
        text: String,
        chunkSize: Int = Int.MAX_VALUE,
    ) {
        val bytes = text.encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(chunkSize, bytes.size - offset)
            checkNotNull(listener).onBytes(bytes, offset, count)
            offset += count
        }
    }

    fun expectOutput(expected: String) {
        val actual = takeOutput()
        check(actual == expected) { "Expected ${expected.toCharArray().map { it.code }}, got ${actual.toCharArray().map { it.code }}" }
    }

    fun takeOutput(): String = output.toString(Charsets.UTF_8).also { output.reset() }
}
