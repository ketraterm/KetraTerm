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
package io.github.ketraterm.workspace

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.shell.integration.ShellCommandInventoryInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** One session's private mailbox. The shell replaces complete snapshots atomically. */
internal class ShellCommandInventoryFiles(
    val directory: Path = Files.createTempDirectory("ketraterm-inventory-"),
    val input: ShellCommandInventoryInput = ShellCommandInventoryInput(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val ready = directory.resolve("path.ready")
    private var stamp: Stamp? = null
    private var pending: ByteArray? = null
    private var scope: CoroutineScope? = null

    fun observe(session: TerminalSession) {
        check(scope == null && !closed.get()) { "Inventory mailbox already observed or closed" }
        val owner = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("shell-inventory"))
        scope = owner
        owner.launch {
            try {
                coroutineScope {
                    val reader =
                        launch {
                            var reportedFailure = false
                            while (isActive) {
                                try {
                                    poll()
                                    reportedFailure = false
                                } catch (failure: IOException) {
                                    if (!reportedFailure) {
                                        LOGGER.log(
                                            System.Logger.Level.WARNING,
                                            "Cannot read shell inventory mailbox",
                                            failure,
                                        )
                                    }
                                    reportedFailure = true
                                }
                                delay(100.milliseconds)
                            }
                        }
                    try {
                        session.state.first { it is TerminalSessionState.Closed }
                    } finally {
                        reader.cancelAndJoin()
                    }
                }
            } finally {
                close()
            }
        }
    }

    /** One bounded read/validation step, also used by deterministic lifecycle tests. */
    internal fun poll() {
        if (closed.get()) return
        val attributes =
            try {
                Files.readAttributes(ready, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return
            }
        val current = Stamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
        if (stamp != current) {
            pending =
                if (attributes.isRegularFile && attributes.size() <= ShellCommandInventoryInput.MAX_SNAPSHOT_BYTES) {
                    Files.newInputStream(ready, NOFOLLOW_LINKS).use { it.readNBytes(ShellCommandInventoryInput.MAX_SNAPSHOT_BYTES + 1) }
                } else {
                    null
                }
            stamp = current
        }
        // File publication can beat the corresponding Ready marker through the PTY.
        // Retain only this bounded snapshot and retry after the live epoch arrives.
        pending?.let { if (input.accept(it)) pending = null }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        input.close()
        scope?.cancel()
        pending = null
        for (path in listOf(ready, directory.resolve("path.pending"), directory)) {
            try {
                Files.deleteIfExists(path)
            } catch (failure: IOException) {
                LOGGER.log(System.Logger.Level.WARNING, "Cannot remove shell inventory mailbox $path", failure)
            }
        }
    }

    private data class Stamp(
        val key: Any?,
        val modified: FileTime,
        val size: Long,
    )

    private companion object {
        val LOGGER: System.Logger = System.getLogger(ShellCommandInventoryFiles::class.java.name)
    }
}
