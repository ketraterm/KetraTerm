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
package io.github.ketraterm.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellIntegrationEventTest {
    @Test
    fun testPromptMarkerEventHasNoExitCodeByDefault() {
        val event = ShellIntegrationEvent(ShellIntegrationMarker.PROMPT_START)

        assertEquals(ShellIntegrationMarker.PROMPT_START, event.marker)
        assertNull(event.exitCode)
    }

    @Test
    fun testCommandFinishedCanCarryExitCode() {
        val event = ShellIntegrationEvent(ShellIntegrationMarker.COMMAND_FINISHED, exitCode = 127)

        assertEquals(ShellIntegrationMarker.COMMAND_FINISHED, event.marker)
        assertEquals(127, event.exitCode)
    }
}
