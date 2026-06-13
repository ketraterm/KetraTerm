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
package io.github.jvterm.pty

import io.github.jvterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PtyConnectorTest {
    @Test
    fun `constructor and operations validate bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            PtyConnector(TestProcess(), readBufferSize = 0)
        }

        val connector = PtyConnector(TestProcess(input = BlockingInputStream()))
        assertThrows(IllegalArgumentException::class.java) { connector.resize(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { connector.resize(1, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            connector.write(byteArrayOf(1), offset = 2, length = 0)
        }
        connector.close()
    }

    @Test
    fun `start can only be called once`() {
        val connector = PtyConnector(TestProcess(input = BlockingInputStream()))
        val listener = RecordingListener()

        connector.start(listener)

        assertThrows(IllegalStateException::class.java) {
            connector.start(listener)
        }

        connector.close()
    }

    @Test
    fun `reader emits bytes in stream order across chunks`() {
        val connector =
            PtyConnector(
                process = TestProcess(input = ByteArrayInputStream("abcdef".ascii())),
                readBufferSize = 2,
            )
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(1000), "reader did not stop")
        assertEquals(listOf("ab", "cd", "ef"), listener.byteEvents.map { it.asciiText() })
    }

    @Test
    fun `large output emits every byte`() {
        val text = "0123456789".repeat(1000)
        val connector =
            PtyConnector(
                process = TestProcess(input = ByteArrayInputStream(text.ascii())),
                readBufferSize = 31,
            )
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(1000), "reader did not stop")
        assertEquals(text.length, listener.byteEvents.sumOf { it.size })
        assertEquals(text, listener.byteEvents.joinToString(separator = "") { it.asciiText() })
    }

    @Test
    fun `write copies requested range and flushes`() {
        val output = RecordingOutputStream()
        val connector = PtyConnector(TestProcess(input = BlockingInputStream(), output = output))

        connector.write("01234".ascii(), offset = 1, length = 3)

        assertEquals("123", output.text())
        assertEquals(1, output.flushes)
        connector.close()
    }

    @Test
    fun `write is ignored after local close`() {
        val output = RecordingOutputStream()
        val connector = PtyConnector(TestProcess(input = BlockingInputStream(), output = output))

        connector.close()
        connector.write("a".ascii())

        assertEquals("", output.text())
    }

    @Test
    fun `resize delegates to process until closed`() {
        val process = TestProcess(input = BlockingInputStream())
        val connector = PtyConnector(process)

        connector.resize(80, 24)
        connector.close()
        connector.resize(100, 40)

        assertEquals(listOf(80 to 24), process.sizes)
    }

    @Test
    fun `close destroys process and closes output once`() {
        val output = RecordingOutputStream()
        val process = TestProcess(input = BlockingInputStream(), output = output)
        val connector = PtyConnector(process)

        connector.close()
        connector.close()

        assertTrue(process.destroyed)
        assertEquals(1, output.closeCount)
    }

    @Test
    fun `reader failure emits error and closes once`() {
        val failure = IOException("read failed")
        val connector = PtyConnector(TestProcess(input = FailingInputStream(failure), blockWaitFor = true))
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(1000), "reader did not stop")
        assertEquals(listOf(failure), listener.errors)
        assertEquals(listOf<Int?>(null), listener.closed)
        assertEquals(failure, connector.failure)
    }

    @Test
    fun `process exit emits exit code once`() {
        val connector = PtyConnector(TestProcess(input = BlockingInputStream(), exitCode = 7))
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinWatcher(1000), "watcher did not stop")
        assertEquals(listOf<Int?>(7), listener.closed)
        assertEquals(7, connector.exitCode)
        connector.close()
    }

    @Test
    fun `reader eof and watcher do not emit duplicate close`() {
        val connector = PtyConnector(TestProcess(input = ByteArrayInputStream(ByteArray(0)), exitCode = 3))
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(1000), "reader did not stop")
        assertTrue(connector.joinWatcher(1000), "watcher did not stop")
        assertEquals(1, listener.closed.size)
        assertEquals(3, listener.closed.single())
    }

    @Test
    fun `reader eof waits for watcher instead of emitting null close`() {
        val process =
            TestProcess(
                input = ByteArrayInputStream(ByteArray(0)),
                exitCode = 7,
                waitForRelease = CountDownLatch(1),
            )
        val connector = PtyConnector(process)
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(1000), "reader did not stop")
        assertEquals(emptyList<Int?>(), listener.closed)

        process.releaseWaitFor()

        assertTrue(connector.joinWatcher(1000), "watcher did not stop")
        assertEquals(listOf<Int?>(7), listener.closed)
    }

    @Test
    fun `close can be called from reader callback without joining itself`() {
        lateinit var connector: PtyConnector
        val process = TestProcess(input = ByteArrayInputStream("x".ascii()))
        val closedInCallback = CountDownLatch(1)
        val listener =
            object : RecordingListener() {
                override fun onBytes(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    connector.close()
                    closedInCallback.countDown()
                }
            }
        connector = PtyConnector(process)

        connector.start(listener)

        assertTrue(closedInCallback.await(1, TimeUnit.SECONDS))
        assertTrue(process.destroyed)
    }

    private open class RecordingListener : TerminalConnectorListener {
        val byteEvents = mutableListOf<ByteArray>()
        val closed = mutableListOf<Int?>()
        val errors = mutableListOf<Throwable>()

        override fun onBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            byteEvents += bytes.copyOfRange(offset, offset + length)
        }

        override fun onClosed(exitCode: Int?) {
            closed += exitCode
        }

        override fun onError(error: Throwable) {
            errors += error
        }
    }

    private class TestProcess(
        override val input: InputStream = ByteArrayInputStream(ByteArray(0)),
        override val output: OutputStream = RecordingOutputStream(),
        private val exitCode: Int = 0,
        private val blockWaitFor: Boolean = false,
        private val waitForRelease: CountDownLatch? = null,
    ) : TerminalProcess {
        var destroyed: Boolean = false
            private set
        val sizes = mutableListOf<Pair<Int, Int>>()

        override fun isAlive(): Boolean = !destroyed

        override fun waitFor(): Int {
            if (blockWaitFor) {
                while (!destroyed) {
                    Thread.sleep(10)
                }
            }
            waitForRelease?.await(1, TimeUnit.SECONDS)
            return exitCode
        }

        override fun destroy() {
            destroyed = true
            if (input is BlockingInputStream) {
                input.release()
            }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            sizes += columns to rows
        }

        fun releaseWaitFor() {
            waitForRelease?.countDown()
        }
    }

    private class RecordingOutputStream : ByteArrayOutputStream() {
        var flushes: Int = 0
            private set
        var closeCount: Int = 0
            private set

        override fun flush() {
            flushes++
        }

        override fun close() {
            closeCount++
            super.close()
        }

        fun text(): String = toString(StandardCharsets.US_ASCII)
    }

    private class BlockingInputStream : InputStream() {
        private val released = CountDownLatch(1)

        override fun read(): Int {
            released.await(1, TimeUnit.SECONDS)
            return -1
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            released.await(1, TimeUnit.SECONDS)
            return -1
        }

        fun release() {
            released.countDown()
        }
    }

    private class FailingInputStream(
        private val failure: IOException,
    ) : InputStream() {
        override fun read(): Int = throw failure

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw failure
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.asciiText(): String = toString(StandardCharsets.US_ASCII)
}
