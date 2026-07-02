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
package io.github.ketraterm.app.completion

import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandaloneCompletionTriggerControllerTest {
    @Test
    fun `schedule coalesces refreshes and requests suggestions for active command snapshot`() {
        val scheduler = FakeScheduler()
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var snapshot: TerminalShellCommandLineSnapshot? = snapshot("git s")
        val controller =
            controller(
                scheduler = scheduler,
                activeCommandLine = { snapshot },
                requestSuggestions = { requested += it },
            )

        controller.scheduleRefresh()
        controller.scheduleRefresh()

        assertEquals(2, scheduler.restartCount)
        assertEquals(0, requested.size)

        scheduler.firePending()

        assertEquals(listOf(snapshot), requested)
    }

    @Test
    fun `refresh hides popup when active command snapshot is unavailable`() {
        val hidden = Counter()
        val controller =
            controller(
                activeCommandLine = { null },
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(1, hidden.count)
    }

    @Test
    fun `refresh does not request suggestions twice for the same active command snapshot`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val activeSnapshot = snapshot("git s")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
            )

        controller.refreshNow()
        controller.refreshNow()

        assertEquals(listOf(activeSnapshot), requested)
    }

    @Test
    fun `refresh requests suggestions again when active command snapshot changes`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var activeSnapshot = snapshot("git s")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
            )

        controller.refreshNow()
        activeSnapshot = snapshot("git st")
        controller.refreshNow()

        assertEquals(listOf(snapshot("git s"), snapshot("git st")), requested)
    }

    @Test
    fun `invalidate last request allows same active command snapshot to refresh again`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val activeSnapshot = snapshot("git s")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
            )

        controller.refreshNow()
        controller.invalidateLastRequest()
        controller.refreshNow()

        assertEquals(listOf(activeSnapshot, activeSnapshot), requested)
    }

    @Test
    fun `refresh requests suggestions again when ranking context changes`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        var rankingContext = "file:///project-a"
        val activeSnapshot = snapshot("git s")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
                rankingContextKey = { rankingContext },
            )

        controller.refreshNow()
        rankingContext = "file:///project-b"
        controller.refreshNow()

        assertEquals(listOf(activeSnapshot, activeSnapshot), requested)
    }

    @Test
    fun `refresh hides popup when command prefix is too short`() {
        val hidden = Counter()
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val controller =
            controller(
                activeCommandLine = { snapshot(" g ") },
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(0, requested.size)
        assertEquals(1, hidden.count)
    }

    @Test
    fun `disabled settings cancel pending refresh and hide popup on refresh`() {
        val scheduler = FakeScheduler()
        val hidden = Counter()
        val controller =
            controller(
                scheduler = scheduler,
                suggestionsEnabled = { false },
                hideSuggestions = hidden::increment,
            )

        controller.scheduleRefresh()
        assertEquals(1, scheduler.restartCount)

        scheduler.firePending()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, hidden.count)
        assertNull(scheduler.pendingAction)
    }

    @Test
    fun `cancel and hide clears pending refresh without requesting suggestions`() {
        val scheduler = FakeScheduler()
        val hidden = Counter()
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val controller =
            controller(
                scheduler = scheduler,
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
            )

        controller.scheduleRefresh()
        controller.cancelAndHide()
        scheduler.firePending()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, hidden.count)
        assertEquals(0, requested.size)
    }

    @Test
    fun `refresh requests suggestions for hyphen option triggers bypassing length rules`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val hidden = Counter()
        val activeSnapshot = snapshot("-")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(listOf(activeSnapshot), requested)
        assertEquals(0, hidden.count)
    }

    @Test
    fun `refresh requests suggestions for path separator triggers bypassing length rules`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val hidden = Counter()
        val activeSnapshot = snapshot("/")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(listOf(activeSnapshot), requested)
        assertEquals(0, hidden.count)
    }

    @Test
    fun `refresh requests suggestions for environment variable triggers bypassing length rules`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val hidden = Counter()
        val activeSnapshot = snapshot("$")
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(listOf(activeSnapshot), requested)
        assertEquals(0, hidden.count)
    }

    @Test
    fun `refresh requests suggestions for single space after word bypassing length rules`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val hidden = Counter()
        val activeSnapshot = snapshot("go ")
        val controller =
            StandaloneCompletionTriggerController(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
                suggestionsEnabled = { true },
                scheduler = FakeScheduler(),
                debounceMillis = 50,
                minimumNonWhitespaceCharacters = 3,
            )

        controller.refreshNow()

        assertEquals(listOf(activeSnapshot), requested)
        assertEquals(0, hidden.count)
    }

    @Test
    fun `refresh hides popup for multiple trailing spaces or space on empty command`() {
        val requested = ArrayList<TerminalShellCommandLineSnapshot>()
        val hidden = Counter()
        var activeSnapshot = snapshot("g  ") // double space
        val controller =
            controller(
                activeCommandLine = { activeSnapshot },
                requestSuggestions = { requested += it },
                hideSuggestions = hidden::increment,
            )

        controller.refreshNow()

        assertEquals(0, requested.size)
        assertEquals(1, hidden.count)

        activeSnapshot = snapshot(" ") // space on empty
        controller.refreshNow()
        assertEquals(0, requested.size)
        assertEquals(2, hidden.count)
    }

    private fun controller(
        activeCommandLine: () -> TerminalShellCommandLineSnapshot? = { snapshot("git s") },
        requestSuggestions: (TerminalShellCommandLineSnapshot) -> Unit = { },
        hideSuggestions: () -> Unit = { },
        rankingContextKey: () -> String? = { null },
        suggestionsEnabled: () -> Boolean = { true },
        scheduler: StandaloneCompletionTriggerScheduler = FakeScheduler(),
    ): StandaloneCompletionTriggerController =
        StandaloneCompletionTriggerController(
            activeCommandLine = activeCommandLine,
            requestSuggestions = requestSuggestions,
            hideSuggestions = hideSuggestions,
            rankingContextKey = rankingContextKey,
            suggestionsEnabled = suggestionsEnabled,
            scheduler = scheduler,
            debounceMillis = 50,
            minimumNonWhitespaceCharacters = 2,
        )

    private fun snapshot(commandText: String): TerminalShellCommandLineSnapshot =
        TerminalShellCommandLineSnapshot(
            commandText = commandText,
            cursorOffset = commandText.length,
            cursorColumn = commandText.length,
            cursorRow = 0,
        )

    private class FakeScheduler : StandaloneCompletionTriggerScheduler {
        var restartCount = 0
            private set
        var cancelCount = 0
            private set
        var lastDelayMillis = -1
            private set
        var pendingAction: (() -> Unit)? = null
            private set

        override fun restart(
            delayMillis: Int,
            action: () -> Unit,
        ) {
            restartCount++
            lastDelayMillis = delayMillis
            pendingAction = action
        }

        override fun cancel() {
            cancelCount++
            pendingAction = null
        }

        fun firePending() {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }

    private class Counter {
        var count = 0
            private set

        fun increment() {
            count++
        }
    }
}
