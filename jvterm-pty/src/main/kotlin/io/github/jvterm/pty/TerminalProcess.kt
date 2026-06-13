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

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.InputStream
import java.io.OutputStream

internal interface TerminalProcess {
    val input: InputStream
    val output: OutputStream

    fun isAlive(): Boolean

    fun waitFor(): Int

    fun destroy()

    fun resize(
        columns: Int,
        rows: Int,
    )
}

internal interface TerminalProcessFactory {
    fun start(options: TerminalPtyOptions): TerminalProcess
}

internal object Pty4jTerminalProcessFactory : TerminalProcessFactory {
    override fun start(options: TerminalPtyOptions): TerminalProcess {
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

        return Pty4jTerminalProcess(builder.start())
    }
}

internal class Pty4jTerminalProcess(
    private val process: PtyProcess,
) : TerminalProcess {
    override val input: InputStream
        get() = process.inputStream

    override val output: OutputStream
        get() = process.outputStream

    override fun isAlive(): Boolean = process.isAlive

    override fun waitFor(): Int = process.waitFor()

    override fun destroy() {
        process.destroy()
    }

    override fun resize(
        columns: Int,
        rows: Int,
    ) {
        process.winSize = WinSize(columns, rows)
    }
}
