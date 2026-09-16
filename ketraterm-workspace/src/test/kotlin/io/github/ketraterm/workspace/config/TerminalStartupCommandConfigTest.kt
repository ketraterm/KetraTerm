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
package io.github.ketraterm.workspace.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalStartupCommandConfigTest {
    @Test
    fun `startup command round trips quotes hashes Windows paths Unicode and significant spaces`(
        @TempDir directory: Path,
    ) {
        val commands =
            listOf(
                "",
                "npm run dev",
                "  echo 'héllo #world'  ",
                "& \"C:\\tools\\new folder\\tool.exe\" 'argument'",
                "python -c \"print('''value''')\"",
                "echo 'trailing quote'",
                "echo \\\\u0022 \\\\u005c #comment",
            )
        val manager = TerminalWorkspaceConfigManager(directory.resolve("config.toml"))
        for (command in commands) {
            val config = TerminalConfig(startupCommand = command)
            manager.save(config)
            assertEquals(config, manager.load(), command)
        }
    }

    @Test
    fun `plain manually configured startup command is accepted`() {
        assertEquals(
            "npm run dev",
            TomlParser.parse("[shell]\nstartup_command = \"npm run dev\" # run once")["shell"]?.get("startup_command"),
        )
    }
}
