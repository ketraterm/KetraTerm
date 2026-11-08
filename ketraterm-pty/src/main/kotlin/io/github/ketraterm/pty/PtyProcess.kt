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

import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.pty4j.windows.conpty.WinConPtyProcess
import java.io.InputStream
import java.io.OutputStream
import com.pty4j.PtyProcess as Pty4jNativeProcess

internal interface PtyProcess {
    val input: InputStream
    val output: OutputStream

    /** Destruction owns stream cleanup; the reader must discard stdout until the backend closes it. */
    val requiresOutputDrainOnClose: Boolean get() = false

    fun foregroundProcessName(): String? = null

    fun isAlive(): Boolean

    fun waitFor(): Int

    fun destroy()

    fun resize(
        columns: Int,
        rows: Int,
    )
}

internal interface PtyProcessFactory {
    fun start(options: PtyOptions): PtyProcess
}

internal object Pty4jProcessFactory : PtyProcessFactory {
    override fun start(options: PtyOptions): PtyProcess {
        val builder =
            PtyProcessBuilder()
                .setCommand(options.command.toTypedArray())
                .setEnvironment(options.environment)
                .setInitialColumns(options.columns)
                .setInitialRows(options.rows)
                .setUseWinConPty(true)

        options.workingDirectory?.let { directory ->
            builder.setDirectory(directory.toString())
        }

        return Pty4jProcess(builder.start())
    }
}

internal class Pty4jProcess(
    private val process: Pty4jNativeProcess,
) : PtyProcess {
    private val foregroundProcessDetector by lazy { PtyForegroundProcessDetector(process) }

    override fun foregroundProcessName(): String? = foregroundProcessDetector.readName()

    override val input: InputStream
        get() = process.inputStream

    override val output: OutputStream
        get() = process.outputStream

    // Pty4J closes ConPTY's output pipe after closing the pseudoconsole. Closing it
    // concurrently with ReadFile can block, while stopping reads can stall console shutdown.
    override val requiresOutputDrainOnClose: Boolean = process is WinConPtyProcess

    override fun isAlive(): Boolean = process.isAlive

    override fun waitFor(): Int = process.waitFor()

    override fun destroy() {
        process.destroy()
        if (requiresOutputDrainOnClose) {
            // TerminateProcess is asynchronous. Await the process (not its output
            // callbacks) so close releases its working directory before returning.
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        process.winSize = WinSize(columns, rows)
    }
}
