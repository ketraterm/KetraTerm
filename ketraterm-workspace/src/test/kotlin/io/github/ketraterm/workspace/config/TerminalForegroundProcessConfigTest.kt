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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalForegroundProcessConfigTest {
    @Test
    fun `process title setting round trips both values`(
        @TempDir directory: Path,
    ) {
        val manager = TerminalWorkspaceConfigManager(directory.resolve("config.toml"))
        for (enabled in listOf(false, true)) {
            val config = TerminalConfig(showForegroundProcessName = enabled)
            manager.save(config)
            assertEquals(config, manager.load())
        }
    }

    @Test
    fun `missing and invalid process title settings use the enabled default`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(file)
        for (content in listOf("", "[behavior]\nshow_foreground_process_name = invalid")) {
            Files.writeString(file, content)
            assertTrue(manager.load().showForegroundProcessName)
        }
    }
}
