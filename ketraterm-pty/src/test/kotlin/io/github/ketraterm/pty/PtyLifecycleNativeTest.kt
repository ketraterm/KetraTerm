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

import com.pty4j.Command
import com.pty4j.PtyProcessBuilder
import io.github.ketraterm.transport.TerminalConnectorListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Tag("native")
class PtyLifecycleNativeTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `local close during sustained output releases the process and native workers`(fromByteCallback: Boolean) {
        NativeOutputFixture().use { fixture ->
            val outputReady = CountDownLatch(1)
            val received = AtomicInteger()
            val remoteClosures = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            fixture.connector.start(
                object : TerminalConnectorListener {
                    override fun onBytes(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        var payloadBytes = 0
                        for (index in offset until offset + length) {
                            if (bytes[index] == 'x'.code.toByte()) payloadBytes++
                        }
                        if (received.addAndGet(payloadBytes) >= 32_768) {
                            outputReady.countDown()
                            if (fromByteCallback) fixture.closeFromCallback()
                        }
                    }

                    override fun onError(error: Throwable) {
                        failure.compareAndSet(null, error)
                    }

                    override fun onClosed(exitCode: Int?) {
                        remoteClosures.incrementAndGet()
                    }
                },
            )
            assertTrue(outputReady.await(15, TimeUnit.SECONDS), "the child did not produce sustained output")
            if (!fromByteCallback) fixture.closeOnWorker()
            fixture.awaitClosed()
            assertTrue(received.get() >= 32_768)
            assertNull(failure.get())
            assertEquals(0, remoteClosures.get())
            assertNull(fixture.connector.failure)
            assertNull(fixture.connector.exitCode)
        }
    }

    @Test
    fun `local close releases an unstarted native connector`() {
        NativeOutputFixture().use { fixture ->
            fixture.closeOnWorker()
            fixture.awaitClosed()
            assertThrows(IllegalStateException::class.java) {
                fixture.connector.start(
                    object : TerminalConnectorListener {
                        override fun onBytes(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) = error("closed connector delivered bytes")

                        override fun onError(error: Throwable) = throw error

                        override fun onClosed(exitCode: Int?) = error("closed connector delivered closure")
                    },
                )
            }
            assertNull(fixture.connector.failure)
            assertNull(fixture.connector.exitCode)
        }
    }

    private class NativeOutputFixture : AutoCloseable {
        private val process =
            PtyProcessBuilder()
                .setCommand(Command.CommandList(outputCommand()))
                .setEnvironment(PtyOptions.defaultEnvironment())
                .setUseWinConPty(true)
                .setInitialColumns(80)
                .setInitialRows(24)
                .start()
        private val processHandle = ProcessHandle.of(process.pid()).orElse(null)
        val connector = PtyConnector(process, readBufferSize = 257)
        private val closing = AtomicBoolean()
        private val closeTask = FutureTask { connector.close() }

        fun closeFromCallback() {
            if (closing.compareAndSet(false, true)) closeTask.run()
        }

        fun closeOnWorker() {
            if (closing.compareAndSet(false, true)) {
                Thread(closeTask, "test-native-pty-close").apply { isDaemon = true }.start()
            }
        }

        fun awaitClosed() {
            closeTask.get(10, TimeUnit.SECONDS)
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the native child did not exit after local close")
            assertTrue(connector.joinReader(10_000), "native stdout reader remained active after local close")
            assertTrue(connector.joinWatcher(10_000), "native process watcher remained active after local close")
            assertFalse(process.isAlive, "the native child remained alive after local close")
        }

        override fun close() {
            var failure: Throwable? = null

            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (next: Throwable) {
                    val previous = failure
                    if (previous == null) {
                        failure = next
                    } else if (previous !== next) {
                        previous.addSuppressed(next)
                    }
                }
            }
            cleanup {
                if (processHandle?.isAlive == true) {
                    processHandle.destroyForcibly()
                    processHandle.onExit().get(10, TimeUnit.SECONDS)
                }
            }
            closeOnWorker()
            cleanup { closeTask.get(10, TimeUnit.SECONDS) }
            cleanup { assertTrue(connector.joinReader(10_000), "native reader did not stop during cleanup") }
            cleanup { assertTrue(connector.joinWatcher(10_000), "native watcher did not stop during cleanup") }
            failure?.let { throw it }
        }

        private fun outputCommand(): List<String> =
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                val script = "while (1) { [Console]::Out.Write('x' * 8192) }"
                listOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE)),
                )
            } else {
                listOf("/bin/sh", "-c", "while :; do printf '%s' '${"x".repeat(8192)}'; done")
            }
    }
}
