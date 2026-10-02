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
package io.github.ketraterm.pty

import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PtyConnectorTest {
    private val connectors = mutableListOf<PtyConnector>()

    @AfterEach
    fun closeConnectors() {
        connectors.forEach(PtyConnector::close)
    }

    @Test
    fun `foreground process metadata is unavailable after close including a close during lookup`() {
        var reads = 0
        lateinit var connector: PtyConnector
        val process =
            TestProcess(readForegroundName = {
                if (++reads == 2) connector.close()
                "vim"
            })
        connector = createConnector(process)
        assertEquals("vim", connector.foregroundProcessName())
        assertNull(connector.foregroundProcessName())
        assertNull(connector.foregroundProcessName())
        assertEquals(2, reads)
    }

    @Test
    fun `constructor and operations validate bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            createConnector(TestProcess(), readBufferSize = 0)
        }

        val connector = createConnector(TestProcess(input = BlockingInputStream()))
        assertThrows(IllegalArgumentException::class.java) { connector.resize(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { connector.resize(1, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            connector.write(byteArrayOf(1), offset = 2, length = 0)
        }
        connector.close()
    }

    @Test
    fun `start can only be called once`() {
        val connector = createConnector(TestProcess(input = BlockingInputStream()))
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
            createConnector(
                process = TestProcess(input = ByteArrayInputStream("abcdef".ascii())),
                readBufferSize = 2,
            )
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(10_000), "reader did not stop")
        assertEquals(listOf("ab", "cd", "ef"), listener.byteEvents.map { it.asciiText() })
    }

    @Test
    fun `large output emits every byte`() {
        val text = "0123456789".repeat(1000)
        val connector =
            createConnector(
                process = TestProcess(input = ByteArrayInputStream(text.ascii())),
                readBufferSize = 31,
            )
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(10_000), "reader did not stop")
        assertEquals(text.length, listener.byteEvents.sumOf { it.size })
        assertEquals(text, listener.byteEvents.joinToString(separator = "") { it.asciiText() })
    }

    @Test
    fun `write copies requested range and flushes`() {
        val output = RecordingOutputStream()
        val connector = createConnector(TestProcess(input = BlockingInputStream(), output = output))

        connector.write("01234".ascii(), offset = 1, length = 3)

        assertEquals("123", output.text())
        assertEquals(1, output.flushes)
        connector.close()
    }

    @Test
    fun `write is ignored after local close`() {
        val output = RecordingOutputStream()
        val connector = createConnector(TestProcess(input = BlockingInputStream(), output = output))

        connector.close()
        connector.write("a".ascii())

        assertEquals("", output.text())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 5])
    fun `connector contract default length writes the remaining byte range`(offset: Int) {
        val output = RecordingOutputStream()
        val connector: TerminalConnector = createConnector(TestProcess(input = BlockingInputStream(), output = output))
        val bytes = "01234".ascii()

        connector.write(bytes, offset = offset)

        assertEquals("01234".substring(offset), output.text())
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 6, Int.MIN_VALUE, Int.MAX_VALUE])
    fun `connector contract rejects invalid default slice before writing`(offset: Int) {
        val output = RecordingOutputStream()
        val connector: TerminalConnector = createConnector(TestProcess(input = BlockingInputStream(), output = output))

        assertThrows(IllegalArgumentException::class.java) {
            connector.write("01234".ascii(), offset = offset)
        }

        assertEquals("", output.text())
        assertEquals(0, output.flushes)
    }

    @Test
    fun `resize delegates to process until closed`() {
        val process = TestProcess(input = BlockingInputStream())
        val connector = createConnector(process)

        connector.resize(80, 24)
        connector.close()
        connector.resize(100, 40)

        assertEquals(listOf(80 to 24), process.sizes)
    }

    @Test
    fun `close destroys process and closes output once`() {
        val output = RecordingOutputStream()
        val process = TestProcess(input = BlockingInputStream(), output = output)
        val connector = createConnector(process)

        connector.close()
        connector.close()

        assertTrue(process.destroyed)
        assertEquals(1, output.closeCount)
    }

    @Test
    fun `reader failure emits the original error and disposes the live process once`() {
        val failure = IOException("read failed")
        val output = RecordingOutputStream()
        val input = FailingInputStream(failure)
        val process = TestProcess(input = input, output = output, blockWaitFor = true)
        val connector = createConnector(process)
        val listener = RecordingListener()

        connector.use { connector ->
            connector.start(listener)
            assertTrue(connector.joinReader(10_000), "reader did not stop")
            assertAll(
                { assertEquals(listOf(failure), listener.errors) },
                { assertEquals(listOf<Int?>(null), listener.closed) },
                { assertSame(failure, connector.failure) },
                { assertTrue(process.destroyed, "fatal read failure must dispose the live process") },
                { assertEquals(1, output.closeCount) },
            )
            connector.close()
            connector.close()
            assertEquals(1, output.closeCount)
            assertEquals(1, input.closeCount)
            assertEquals(1, process.destroyCount)
            assertSame(failure, connector.failure)
            assertEquals(listOf<Int?>(null), listener.closed)
        }
    }

    @Test
    fun `read failure preserves delivered output and the original cause through later process exit`() {
        val failure = IOException("read failed after output")
        val prefix = ByteArrayInputStream("before failure".ascii())
        val input =
            object : InputStream() {
                override fun read(): Int = prefix.read().takeIf { it >= 0 } ?: throw failure
            }
        val process = TestProcess(input = input, blockWaitFor = true)
        val connector = createConnector(process, readBufferSize = 3)
        val listener = RecordingListener()
        connector.use { connector ->
            connector.start(listener)
            assertTrue(connector.joinReader(10_000), "reader did not stop after failure")
            connector.close()
            assertTrue(connector.joinWatcher(10_000), "watcher did not stop after disposal")
            assertAll(
                { assertEquals("before failure", listener.byteEvents.joinToString("") { it.asciiText() }) },
                { assertEquals(listOf(failure), listener.errors) },
                { assertEquals(listOf<Int?>(null), listener.closed) },
                { assertSame(failure, connector.failure) },
                { assertTrue(process.destroyed) },
            )
        }
    }

    @Test
    fun `local close during a pending read suppresses teardown errors and remote closure`() {
        val readEntered = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val input =
            object : InputStream() {
                override fun read(): Int {
                    readEntered.countDown()
                    releaseFailure.await()
                    throw IOException("stream closed during local shutdown")
                }
            }
        var outputCloses = 0
        val output =
            object : ByteArrayOutputStream() {
                override fun close() {
                    outputCloses++
                    releaseFailure.countDown()
                    super.close()
                }
            }
        val process = TestProcess(input = input, output = output, blockWaitFor = true)
        val connector = createConnector(process)
        val listener = RecordingListener()
        try {
            connector.start(listener)
            assertTrue(readEntered.await(10, TimeUnit.SECONDS), "reader did not enter its pending read")
            connector.close()
            assertTrue(connector.joinReader(10_000), "reader did not stop after local shutdown")
            assertTrue(connector.joinWatcher(10_000), "watcher did not stop after local shutdown")
            connector.close()
            assertAll(
                { assertTrue(listener.errors.isEmpty()) },
                { assertTrue(listener.closed.isEmpty()) },
                { assertNull(connector.failure) },
                { assertNull(connector.exitCode) },
                { assertTrue(process.destroyed) },
                { assertEquals(1, outputCloses) },
            )
        } finally {
            releaseFailure.countDown()
            connector.close()
        }
    }

    @Test
    fun `local shutdown closes the input stream to release a blocked reader`() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val inputCloses = AtomicInteger()
        val input =
            object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    released.await()
                    throw IOException("input closed")
                }

                override fun close() {
                    inputCloses.incrementAndGet()
                    released.countDown()
                }
            }
        val process = TestProcess(input = input, blockWaitFor = true)
        val connector = createConnector(process)
        val listener = RecordingListener()
        try {
            connector.start(listener)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            connector.close()
            assertTrue(connector.joinReader(10_000))
            assertTrue(connector.joinWatcher(10_000))
            connector.close()
            assertEquals(1, inputCloses.get())
            assertEquals(1, process.destroyCount)
            assertTrue(listener.errors.isEmpty())
            assertTrue(listener.closed.isEmpty())
        } finally {
            released.countDown()
            connector.close()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 4, 8192])
    fun `process exit before the final read still delivers every byte before closure`(readBufferSize: Int) {
        val input = GatedInputStream("LAST LINE\r\n".ascii())
        val processExited = CountDownLatch(1)
        val process =
            TestProcess(
                input = input,
                exitCode = 7,
                waitForRelease = CountDownLatch(1),
                waitForCompleted = processExited,
            )
        val connector = createConnector(process, readBufferSize)
        val events = CopyOnWriteArrayList<String>()
        val listener =
            object : RecordingListener() {
                override fun onBytes(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    events += "bytes:" + bytes.copyOfRange(offset, offset + length).asciiText()
                }

                override fun onClosed(exitCode: Int?) {
                    events += "closed:$exitCode"
                }
            }
        try {
            connector.start(listener)
            assertTrue(input.readEntered.await(10, TimeUnit.SECONDS), "reader did not enter its final read")
            process.releaseWaitFor()
            assertTrue(processExited.await(10, TimeUnit.SECONDS), "process exit was not observed")
            input.release()
            assertTrue(connector.joinReader(10_000), "reader did not finish")
            assertTrue(connector.joinWatcher(10_000), "watcher did not finish")
            assertEquals("LAST LINE\r\n".chunked(readBufferSize).map { "bytes:$it" } + "closed:7", events.toList())
        } finally {
            input.release()
            process.releaseWaitFor()
            connector.close()
        }
    }

    @Test
    fun `process exit emits exit code once`() {
        val connector = createConnector(TestProcess(input = ByteArrayInputStream(ByteArray(0)), exitCode = 7))
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinWatcher(10_000), "watcher did not stop")
        assertEquals(listOf<Int?>(7), listener.closed)
        assertEquals(7, connector.exitCode)
        connector.close()
    }

    @Test
    fun `reader eof and watcher do not emit duplicate close`() {
        val connector = createConnector(TestProcess(input = ByteArrayInputStream(ByteArray(0)), exitCode = 3))
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(10_000), "reader did not stop")
        assertTrue(connector.joinWatcher(10_000), "watcher did not stop")
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
        val connector = createConnector(process)
        val listener = RecordingListener()

        connector.start(listener)

        assertTrue(connector.joinReader(10_000), "reader did not stop")
        assertEquals(emptyList<Int?>(), listener.closed)

        process.releaseWaitFor()

        assertTrue(connector.joinWatcher(10_000), "watcher did not stop")
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
        connector = createConnector(process)

        connector.start(listener)

        assertTrue(closedInCallback.await(10, TimeUnit.SECONDS))
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
        private val waitForCompleted: CountDownLatch? = null,
        private val readForegroundName: () -> String? = { null },
    ) : PtyProcess {
        private val destroyedSignal = CountDownLatch(1)

        @Volatile
        var destroyed: Boolean = false
            private set
        var destroyCount: Int = 0
            private set
        val sizes = mutableListOf<Pair<Int, Int>>()

        override fun isAlive(): Boolean = !destroyed

        override fun foregroundProcessName(): String? = readForegroundName()

        override fun waitFor(): Int {
            if (blockWaitFor) {
                destroyedSignal.await()
            }
            waitForRelease?.await()
            waitForCompleted?.countDown()
            return exitCode
        }

        override fun destroy() {
            destroyCount++
            destroyed = true
            destroyedSignal.countDown()
            releaseWaitFor()
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
            released.await()
            return -1
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            released.await()
            return -1
        }

        fun release() {
            released.countDown()
        }
    }

    private class GatedInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        val readEntered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val content = ByteArrayInputStream(bytes)

        override fun read(): Int {
            readEntered.countDown()
            released.await()
            return content.read()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readEntered.countDown()
            released.await()
            return content.read(buffer, offset, length)
        }

        fun release() {
            released.countDown()
        }
    }

    private class FailingInputStream(
        private val failure: IOException,
    ) : InputStream() {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
        }

        override fun read(): Int = throw failure

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw failure
    }

    private fun createConnector(
        process: PtyProcess,
        readBufferSize: Int = 8192,
    ): PtyConnector = PtyConnector(process, readBufferSize).also(connectors::add)

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.asciiText(): String = toString(StandardCharsets.US_ASCII)
}
