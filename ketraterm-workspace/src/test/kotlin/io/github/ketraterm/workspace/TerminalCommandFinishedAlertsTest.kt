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

import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import kotlin.test.*

class TerminalCommandFinishedAlertsTest {
    @Test
    fun `inclusive threshold uses captured duration rather than wall clock timestamps`() {
        assertFalse(eligible(durationMillis = 9_999))
        assertTrue(eligible(durationMillis = 10_000))
        assertTrue(eligible(durationMillis = Long.MAX_VALUE))
        assertTrue(eligible(metadata = metadata().copy(startedAtEpochMillis = Long.MAX_VALUE, finishedAtEpochMillis = Long.MIN_VALUE)))
    }

    @Test
    fun `disabled focus denial missing or invalid duration suppress alerts`() {
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(focused = true))
        assertFalse(eligible(notificationsAllowed = false))
        assertFalse(eligible(durationMillis = null))
        assertFalse(eligible(durationMillis = -1))
    }

    @Test
    fun `only explicit completed lifecycles qualify regardless of command text and exit status`() {
        for (lifecycle in listOf(
            TerminalShellIntegrationCommandLifecycle.NONE,
            TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
            TerminalShellIntegrationCommandLifecycle.RUNNING,
            TerminalShellIntegrationCommandLifecycle.ABANDONED,
            Int.MAX_VALUE,
        )) {
            assertFalse(eligible(metadata = metadata().copy(lifecycle = lifecycle)))
        }
        for (lifecycle in listOf(
            TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            TerminalShellIntegrationCommandLifecycle.FAILED,
            TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN,
        )) {
            assertTrue(eligible(metadata = metadata().copy(lifecycle = lifecycle, commandText = null, exitCode = null)))
        }
    }

    @Test
    fun `threshold range has explicit defaults bounds and overflow safe comparison`() {
        assertEquals(10, TerminalCommandFinishedAlerts.DEFAULT_MINIMUM_DURATION_SECONDS)
        assertEquals(1, TerminalCommandFinishedAlerts.MINIMUM_DURATION_SECONDS)
        assertEquals(86_400, TerminalCommandFinishedAlerts.MAXIMUM_DURATION_SECONDS)
        assertTrue(eligible(durationMillis = 1_000, minimumDurationSeconds = 1))
        assertFalse(eligible(durationMillis = 86_399_999, minimumDurationSeconds = 86_400))
        assertTrue(eligible(durationMillis = 86_400_000, minimumDurationSeconds = 86_400))
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 86_401, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { eligible(minimumDurationSeconds = invalid) }
        }
    }

    private fun eligible(
        metadata: TerminalShellIntegrationCommandMetadata = metadata(),
        durationMillis: Long? = 10_000,
        enabled: Boolean = true,
        minimumDurationSeconds: Int = 10,
        focused: Boolean = false,
        notificationsAllowed: Boolean = true,
    ): Boolean =
        TerminalCommandFinishedAlerts.shouldNotify(
            metadata,
            durationMillis,
            enabled,
            minimumDurationSeconds,
            focused,
            notificationsAllowed,
        )

    private fun metadata() =
        TerminalShellIntegrationCommandMetadata(
            1,
            TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            "build",
            null,
            0,
            0,
            10_000,
        )
}
