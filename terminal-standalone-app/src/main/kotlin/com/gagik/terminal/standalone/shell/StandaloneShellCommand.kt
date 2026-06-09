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
package com.gagik.terminal.standalone.shell

import com.gagik.terminal.pty.TerminalPtyOptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the shell command used by the standalone host.
 */
internal object StandaloneShellCommand {
    fun resolve(args: List<String>): List<String> =
        if (args.isNotEmpty()) {
            args
        } else if (isWindows()) {
            windowsPowerShellCommand()
        } else {
            TerminalPtyOptions.defaultCommand()
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private fun windowsPowerShellCommand(): List<String> {
        val systemRoot = System.getenv("SystemRoot")
        if (!systemRoot.isNullOrBlank()) {
            val powershell =
                Path.of(
                    systemRoot,
                    "System32",
                    "WindowsPowerShell",
                    "v1.0",
                    "powershell.exe",
                )
            if (Files.isRegularFile(powershell)) {
                return listOf(powershell.toString(), "-NoLogo")
            }
        }

        return listOf("powershell.exe", "-NoLogo")
    }
}
