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
package io.github.ketraterm.app.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InlineHistorySuggestionsConfigTest {
    @Test
    fun `inline history is opt in and round trips independently of popup settings`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("config.toml")
        val manager = KetraTermConfigManager(path)
        assertFalse(manager.load().inlineHistorySuggestionsEnabled)
        for (enabled in listOf(true, false)) {
            val config = KetraTermConfig(inlineHistorySuggestionsEnabled = enabled, shellSuggestionsEnabled = !enabled)
            manager.save(config)
            assertEquals(config, manager.load())
        }
        for (content in listOf("[behavior]", "[behavior]\ninline_history_suggestions_enabled = invalid")) {
            Files.writeString(path, content)
            assertFalse(manager.load().inlineHistorySuggestionsEnabled)
        }
    }
}
