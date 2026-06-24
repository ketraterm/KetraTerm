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
package io.github.jvterm.ssh

import io.github.jvterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshConnectorTest {
    @Test
    fun `constructor and operations validate bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            options(host = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            options(port = 0)
        }

        val connector = SshConnector(options(), FakeShellClient())
        assertThrows(IllegalArgumentException::class.java) { connector.resize(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { connector.resize(1, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            connector.write(byteArrayOf(1), offset = 2, length = 0)
        }
        connector.close()
    }

    @Test
    fun `start can only be called once`() {
        val connector = SshConnector(options(), FakeShellClient())
        val listener = RecordingListener()

        connector.start(listener)

        assertThrows(IllegalStateException::class.java) {
            connector.start(listener)
        }

        connector.close()
    }

    @Test
    fun `remote bytes are delivered in order`() {
        val shell = FakeShell()
        val client = FakeShellClient(shell)
        val connector = SshConnector(options(), client)
        val listener = RecordingListener()

        connector.start(listener)
        shell.emit("ab".ascii())
        shell.emit("cd".ascii())

        assertEquals(listOf("ab", "cd"), listener.byteEvents.map { it.asciiText() })
        connector.close()
    }

    @Test
    fun `write copies requested range and flushes to shell`() {
        val shell = FakeShell()
        val connector = SshConnector(options(), FakeShellClient(shell))

        connector.start(RecordingListener())
        connector.write("01234".ascii(), offset = 1, length = 3)

        assertEquals("123", shell.writtenBytes.asciiText())
        connector.close()
    }

    @Test
    fun `write is ignored after local close`() {
        val shell = FakeShell()
        val connector = SshConnector(options(), FakeShellClient(shell))

        connector.start(RecordingListener())
        connector.close()
        connector.write("x".ascii())

        assertEquals("", shell.writtenBytes.asciiText())
    }

    @Test
    fun `resize delegates to open shell until closed`() {
        val shell = FakeShell()
        val connector = SshConnector(options(), FakeShellClient(shell))

        connector.start(RecordingListener())
        connector.resize(100, 40)
        connector.close()
        connector.resize(120, 50)

        assertEquals(listOf(100 to 40), shell.sizes)
    }

    @Test
    fun `resize before start is used for initial ssh pty allocation`() {
        val client = FakeShellClient()
        val connector = SshConnector(options(), client)

        connector.resize(120, 35)
        connector.start(RecordingListener())

        assertEquals(120, client.openedOptions?.columns)
        assertEquals(35, client.openedOptions?.rows)
        connector.close()
    }

    @Test
    fun `open failure emits error and closes once`() {
        val failure = IOException("connect failed")
        val connector = SshConnector(options(), FakeShellClient(openFailure = failure))
        val listener = RecordingListener()

        connector.start(listener)

        assertEquals(listOf(failure), listener.errors)
        assertEquals(listOf<Int?>(null), listener.closed)
        assertEquals(failure, connector.failure)
    }

    @Test
    fun `remote close emits exit code once`() {
        val shell = FakeShell(exitCode = 7)
        val connector = SshConnector(options(), FakeShellClient(shell))
        val listener = RecordingListener()

        connector.start(listener)
        shell.releaseClose()

        assertTrue(connector.joinWatcher(1000), "watcher did not stop")
        assertEquals(listOf<Int?>(7), listener.closed)
    }

    @Test
    fun `remote close failure emits error and closes once`() {
        val failure = IOException("channel failed")
        val shell = FakeShell(closeFailure = failure)
        val connector = SshConnector(options(), FakeShellClient(shell))
        val listener = RecordingListener()

        connector.start(listener)
        shell.releaseClose()

        assertTrue(connector.joinWatcher(1000), "watcher did not stop")
        assertEquals(listOf(failure), listener.errors)
        assertEquals(listOf<Int?>(null), listener.closed)
        assertEquals(failure, connector.failure)
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

    private class FakeShellClient(
        private val shell: FakeShell = FakeShell(),
        private val openFailure: IOException? = null,
    ) : SshShellClient {
        var openedOptions: SshOptions? = null
            private set

        override fun open(
            options: SshOptions,
            output: SshShellOutput,
        ): SshShell {
            openFailure?.let { throw it }
            openedOptions = options
            shell.output = output
            return shell
        }
    }

    private class FakeShell(
        private val exitCode: Int? = 0,
        private val closeFailure: IOException? = null,
    ) : SshShell {
        private val closeLatch = CountDownLatch(1)
        private val capturedWrites = ArrayList<Byte>()
        var output: SshShellOutput? = null
        var closed: Boolean = false
            private set
        val sizes = mutableListOf<Pair<Int, Int>>()
        val writtenBytes: ByteArray
            get() = ByteArray(capturedWrites.size) { index -> capturedWrites[index] }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var index = 0
            while (index < length) {
                capturedWrites += bytes[offset + index]
                index++
            }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            sizes += columns to rows
        }

        override fun waitForClosed(): Int? {
            closeLatch.await(1, TimeUnit.SECONDS)
            closeFailure?.let { throw it }
            return exitCode
        }

        override fun close() {
            closed = true
            closeLatch.countDown()
        }

        fun emit(bytes: ByteArray) {
            output?.emit(bytes, 0, bytes.size)
        }

        fun releaseClose() {
            closeLatch.countDown()
        }
    }

    private fun options(
        host: String = "example.com",
        port: Int = 22,
    ): SshOptions =
        SshOptions(
            host = host,
            username = "user",
            port = port,
            authentication = listOf(SshAuthentication.Password("secret")),
        )

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.asciiText(): String = toString(StandardCharsets.US_ASCII)
}
