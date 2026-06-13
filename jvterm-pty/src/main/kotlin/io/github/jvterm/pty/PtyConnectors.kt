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

import io.github.jvterm.transport.TerminalConnector
import java.io.IOException
import java.nio.file.Path

/**
 * Factory for local PTY-backed terminal connectors.
 */
object PtyConnectors {
    /**
     * Creates a connector for a new local PTY process.
     *
     * Callers should depend on [TerminalConnector] instead of PTY4J process
     * classes.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun create(
        command: List<String>,
        env: Map<String, String> = System.getenv(),
        workingDirectory: Path? = null,
        columns: Int = 80,
        rows: Int = 24,
    ): TerminalConnector {
        val options =
            PtyOptions(
                command = command,
                environment = env,
                workingDirectory = workingDirectory,
                columns = columns,
                rows = rows,
            )
        return create(options, Pty4jProcessFactory)
    }

    internal fun create(
        options: PtyOptions,
        processFactory: PtyProcessFactory,
    ): PtyConnector {
        val process = processFactory.start(options)
        return PtyConnector(
            process = process,
            readBufferSize = options.readBufferSize,
            readerThreadName = options.readerThreadName,
            watcherThreadName = options.watcherThreadName,
        )
    }
}
