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

import com.pty4j.PtyProcess
import com.pty4j.unix.UnixPtyProcess
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.time.Instant

/** Native foreground-group lookup on Unix; newest-descendant heuristic on Windows. */
internal class PtyForegroundProcessDetector(
    private val process: PtyProcess,
) {
    private val root by lazy { ProcessHandle.of(process.pid()).orElse(null) }

    fun readName(): String? {
        if (!process.isAlive) return null
        val root = root ?: return null
        if (!root.isAlive) return null
        val foreground =
            when {
                process is UnixPtyProcess -> {
                    val descriptor = process.pty.masterFD
                    if (descriptor < 0) return null
                    val group = PosixHolder.libc?.tcgetpgrp(descriptor) ?: return null
                    if (group <= 0) return null
                    ProcessHandle.of(group.toLong()).orElse(null)
                }
                Platform.isWindows() -> newestDescendant(root)
                else -> null
            } ?: return null
        val command = foreground.info().command().orElse(null) ?: return null
        if (!root.isAlive || !foreground.isAlive || !process.isAlive) return null
        return command.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank)
    }

    private interface Posix : Library {
        fun tcgetpgrp(descriptor: Int): Int
    }

    private object PosixHolder {
        val libc: Posix? =
            try {
                Native.load(Platform.C_LIBRARY_NAME, Posix::class.java)
            } catch (failure: UnsatisfiedLinkError) {
                System
                    .getLogger(PtyForegroundProcessDetector::class.java.name)
                    .log(System.Logger.Level.WARNING, "Foreground process lookup is unavailable", failure)
                null
            }
    }

    companion object {
        private const val MAX_DESCENDANTS = 256

        /** Returns null for oversized trees or missing start times, rather than guessing from a partial snapshot. */
        internal fun newestDescendant(root: ProcessHandle): ProcessHandle? {
            var newest: ProcessHandle = root
            var newestStart = Instant.MIN
            root.descendants().use { descendants ->
                val iterator = descendants.iterator()
                var visited = 0
                while (iterator.hasNext()) {
                    if (++visited > MAX_DESCENDANTS) return null
                    val candidate = iterator.next()
                    if (!candidate.isAlive) continue
                    val started = candidate.info().startInstant().orElse(null) ?: return null
                    if (started > newestStart || (started == newestStart && candidate.pid() > newest.pid())) {
                        newest = candidate
                        newestStart = started
                    }
                }
            }
            return newest.takeIf { it.isAlive }
        }
    }
}
