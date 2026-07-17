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
package io.github.ketraterm.intellij.ui

import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntellijCompletionTriggerControllerTest {
    @Test
    fun `scheduled refresh is coalesced and evaluates latest snapshot`() {
        val scheduler = RecordingScheduler()
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var active = snapshot("git s")
        val controller = controller(scheduler, { active }, requested::add)

        controller.scheduleRefresh()
        active = snapshot("git st")
        controller.scheduleRefresh()
        scheduler.fire()

        assertEquals(listOf(snapshot("git st")), requested)
    }

    @Test
    fun `same command refreshes when ranking directory changes`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var directory = "file:///project-a"
        val active = snapshot("git s")
        val controller =
            controller(
                scheduler = RecordingScheduler(),
                activeCommandLine = { active },
                requestSuggestions = requested::add,
                rankingContextKey = { directory },
            )

        controller.refreshNow()
        directory = "file:///project-b"
        controller.refreshNow()

        assertEquals(listOf(active, active), requested)
    }

    @Test
    fun `completed directory path remains eligible for a refreshed popup`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var active = snapshot("cd A/")
        val controller = controller(RecordingScheduler(), { active }, requested::add)

        controller.refreshNow()
        active = snapshot("cd A/B")
        controller.refreshNow()

        assertEquals(listOf(snapshot("cd A/"), snapshot("cd A/B")), requested)
    }

    @Test
    fun `disabled suggestions cancel pending action and hide`() {
        val scheduler = RecordingScheduler()
        var hidden = 0
        val controller =
            IntellijCompletionTriggerController(
                activeCommandLine = { snapshot("git s") },
                requestSuggestions = {},
                hideSuggestions = { hidden++ },
                rankingContextKey = { null },
                suggestionsEnabled = { false },
                scheduler = scheduler,
                commandSpecs = emptyList(),
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )

        controller.scheduleRefresh()
        scheduler.fire()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, hidden)
        assertNull(scheduler.pending)
    }

    private fun controller(
        scheduler: RecordingScheduler,
        activeCommandLine: () -> TerminalShellCommandLineSnapshot,
        requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit,
        rankingContextKey: () -> String? = { null },
    ): IntellijCompletionTriggerController =
        IntellijCompletionTriggerController(
            activeCommandLine = activeCommandLine,
            requestSuggestions = requestSuggestions,
            hideSuggestions = {},
            rankingContextKey = rankingContextKey,
            suggestionsEnabled = { true },
            scheduler = scheduler,
            commandSpecs = emptyList(),
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )

    private fun snapshot(command: String): TerminalShellCommandLineSnapshot =
        TerminalShellCommandLineSnapshot(command, command.length, cursorColumn = command.length, cursorRow = 2)

    private class RecordingScheduler : IntellijCompletionTriggerScheduler {
        var pending: (() -> Unit)? = null
        var cancelCount = 0

        override fun restart(
            delayMillis: Int,
            action: () -> Unit,
        ) {
            pending = action
        }

        override fun cancel() {
            cancelCount++
            pending = null
        }

        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
        }
    }
}
