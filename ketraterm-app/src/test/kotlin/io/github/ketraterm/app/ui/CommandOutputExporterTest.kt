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
package io.github.ketraterm.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

class CommandOutputExporterTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `blocked writer leaves EDT available and writes captured unicode output`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerOnEdt = AtomicBoolean()
        val failures = mutableListOf<Exception>()
        val destination = directory.resolve("output.txt")
        val text = "saved output λ 🚀\n"
        val exporter =
            CommandOutputExporter(write = { path, output ->
                writerOnEdt.set(SwingUtilities.isEventDispatchThread())
                entered.countDown()
                release.await()
                Files.writeString(path, output)
            })
        lateinit var job: Job
        try {
            SwingUtilities.invokeAndWait { job = exporter.export(destination, text, failures::add) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "writer did not enter")
            assertFalse(writerOnEdt.get())
            SwingUtilities.invokeAndWait { assertFalse(job.isCompleted) }
            release.countDown()
            runBlocking { withTimeout(10_000) { job.join() } }
            assertEquals(text, Files.readString(destination))
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            exporter.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failure reaches EDT with original cause while cancellation stays silent`(cancel: Boolean) {
        val failure = if (cancel) CancellationException("cancelled") else IOException("write failed")
        val failures = mutableListOf<Exception>()
        val exporter = CommandOutputExporter(write = { _, _ -> throw failure })
        lateinit var job: Job
        try {
            SwingUtilities.invokeAndWait {
                job =
                    exporter.export(directory.resolve("output.txt"), "text") {
                        assertTrue(SwingUtilities.isEventDispatchThread())
                        failures += it
                    }
            }
            runBlocking { withTimeout(10_000) { job.join() } }
            if (cancel) assertTrue(failures.isEmpty()) else assertSame(failure, failures.single())
        } finally {
            exporter.close()
        }
    }

    @Test
    fun `disposal cancels exports and suppresses a noninterruptible writer failure`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = mutableListOf<Exception>()
        val exporter =
            CommandOutputExporter(write = { _, _ ->
                entered.countDown()
                var released = false
                while (!released) {
                    try {
                        release.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Model a filesystem operation that cannot finish until its external dependency does.
                    }
                }
                throw IOException("late write failure")
            })
        lateinit var job: Job
        try {
            SwingUtilities.invokeAndWait { job = exporter.export(directory.resolve("output.txt"), "text", failures::add) }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "writer did not enter")
            SwingUtilities.invokeAndWait {
                exporter.close()
                assertTrue(job.isCancelled)
            }
            release.countDown()
            runBlocking { withTimeout(10_000) { job.join() } }
            SwingUtilities.invokeAndWait {
                val rejected = exporter.export(directory.resolve("later.txt"), "later", failures::add)
                assertTrue(rejected.isCancelled)
            }
            assertTrue(failures.isEmpty())
            assertFalse(Files.exists(directory.resolve("later.txt")))
        } finally {
            release.countDown()
            exporter.close()
        }
    }
}
