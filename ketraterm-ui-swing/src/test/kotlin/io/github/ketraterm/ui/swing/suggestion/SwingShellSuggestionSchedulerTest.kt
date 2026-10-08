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
package io.github.ketraterm.ui.swing.suggestion

import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingShellSuggestionSchedulerTest {
    @Test
    fun `invalidation before the initial revision suppresses the unchanged initial snapshot`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.revisions.value = -1
            fixture.start()
            fixture.scheduler.onInvalidated()
            fixture.revisions.value = 0
            runCurrent()
            fixture.scheduler.refresh()
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())

            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `a changed initial snapshot can refresh after invalidation before observation starts`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.revisions.value = -1
            fixture.start()
            fixture.scheduler.onInvalidated()
            fixture.active = snapshot("ls -a")
            fixture.revisions.value = 0
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("ls -a")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `initial observation does not cancel independently captured suggestions`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.focused = false
            fixture.start()
            runCurrent()
            advanceTimeBy(100.milliseconds)
            runCurrent()

            assertEquals(0, fixture.hideCount)
            assertTrue(fixture.requests.isEmpty())
            fixture.revisions.value = 1
            runCurrent()
            assertEquals(1, fixture.hideCount)
            fixture.scheduler.stop()
        }

    @Test
    fun `unstarted scheduler observes nothing and rejects refreshes`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.scheduler.refresh()
            fixture.scheduler.refreshNow()
            fixture.scheduler.onFocusGained()
            fixture.scheduler.onFocusLost()
            fixture.scheduler.onInvalidated()
            fixture.scheduler.onEligibilityChanged(true)
            advanceTimeBy(100.milliseconds)
            runCurrent()

            assertEquals(0, fixture.revisions.subscriptionCount.value)
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.hideCount)
        }

    @Test
    fun `starting the same binding twice retains exactly one observation and pending request`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            advanceTimeBy(50.milliseconds)
            fixture.start()
            assertEquals(1, fixture.revisions.subscriptionCount.value)
            advanceTimeBy(25.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("git s")), fixture.requests)
            fixture.scheduler.stop()
            runCurrent()
            assertEquals(0, fixture.revisions.subscriptionCount.value)
        }

    @Test
    fun `shell edits debounce the latest snapshot`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            fixture.active = snapshot("git st")
            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("git st")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `burst edits wait a complete debounce interval after the last edit`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.active = snapshot("gi")
            fixture.start()
            runCurrent()
            advanceTimeBy(50.milliseconds)
            fixture.active = snapshot("git")
            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(74.milliseconds)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("git")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `changed shell snapshot hides old results before debounce fires`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            val hides = fixture.hideCount
            fixture.active = snapshot("git st")
            fixture.revisions.value = 1
            runCurrent()

            assertEquals(hides + 1, fixture.hideCount)
            assertEquals(listOf(snapshot("git s")), fixture.requests)
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s"), snapshot("git st")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `input invalidation suppresses unchanged state until a new shell edit`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            fixture.scheduler.refreshNow()
            fixture.scheduler.onInvalidated()
            fixture.scheduler.refresh()
            fixture.scheduler.onFocusGained()
            fixture.scheduler.onEligibilityChanged(true)
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertEquals(1, fixture.requests.size)

            fixture.active = snapshot("git st")
            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s"), snapshot("git st")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `focus loss cancels delayed work and focus return uses the same observation`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            fixture.focused = false
            fixture.scheduler.onFocusLost()
            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())
            assertEquals(1, fixture.revisions.subscriptionCount.value)

            fixture.focused = true
            fixture.scheduler.onFocusGained()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s")), fixture.requests)
            assertEquals(1, fixture.revisions.subscriptionCount.value)
            fixture.scheduler.stop()
        }

    @Test
    fun `ineligible viewport cancels automatic work and returning schedules a refresh`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            fixture.eligible = false
            fixture.scheduler.onEligibilityChanged(false)
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())

            fixture.eligible = true
            fixture.scheduler.onEligibilityChanged(true)
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `feedback and explicit refresh invalidate equal-text deduplication`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            fixture.scheduler.refreshNow()
            fixture.scheduler.refreshNow()
            assertEquals(1, fixture.requests.size)

            assertNotNull(fixture.feedbackHandler).onSuggestionFeedback(feedback())
            fixture.scheduler.refreshNow()
            assertEquals(2, fixture.requests.size)

            fixture.scheduler.refresh()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(3, fixture.requests.size)
            fixture.scheduler.stop()
        }

    @Test
    fun `stop releases observation without hiding an explicit request or cancelling its owner`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            val hides = fixture.hideCount
            fixture.scheduler.stop()
            runCurrent()
            fixture.revisions.value = 1
            fixture.scheduler.refresh()
            advanceTimeBy(100.milliseconds)
            runCurrent()

            assertEquals(0, fixture.revisions.subscriptionCount.value)
            assertEquals(hides, fixture.hideCount)
            assertTrue(fixture.requests.isEmpty())
            assertTrue(backgroundScope.isActive)
        }

    @Test
    fun `a new binding cancels old debounce and stops observing the old source`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            advanceTimeBy(50.milliseconds)
            val next = MutableStateFlow(0L)
            fixture.scheduler.start({ snapshot("ls -a") }, next)
            runCurrent()
            assertEquals(0, fixture.revisions.subscriptionCount.value)
            assertEquals(1, next.subscriptionCount.value)
            fixture.revisions.value = 1
            advanceTimeBy(74.milliseconds)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())
            advanceTimeBy(1.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("ls -a")), fixture.requests)
            fixture.scheduler.stop()
            runCurrent()
            assertEquals(0, next.subscriptionCount.value)
        }

    @Test
    fun `reentrant stop during hide cannot restart obsolete work`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            fixture.onHide = fixture.scheduler::stop
            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(100.milliseconds)
            runCurrent()

            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.revisions.subscriptionCount.value)
        }

    @Test
    fun `reentrant replacement during hide retains only the replacement observation`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            val next = MutableStateFlow(0L)
            fixture.onHide = {
                fixture.onHide = null
                fixture.scheduler.start({ snapshot("ls -a") }, next)
            }
            fixture.revisions.value = 1
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("ls -a")), fixture.requests)
            assertEquals(0, fixture.revisions.subscriptionCount.value)
            assertEquals(1, next.subscriptionCount.value)
            fixture.scheduler.stop()
        }

    @Test
    fun `snapshot capture that replaces the binding cannot submit the old snapshot`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            val next = MutableStateFlow(-1L)
            fixture.scheduler.start(
                {
                    fixture.scheduler.start({ snapshot("ls -a") }, next)
                    snapshot("git s")
                },
                fixture.revisions,
            )
            runCurrent()
            fixture.scheduler.refreshNow()
            assertTrue(fixture.requests.isEmpty())
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()

            assertEquals(listOf(snapshot("ls -a")), fixture.requests)
            fixture.scheduler.stop()
        }

    @Test
    fun `input invalidation commits suppression before a hide failure propagates`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            val failure = IllegalStateException("host popup failed")
            fixture.onHide = { throw failure }

            assertSame(failure, assertFailsWith<IllegalStateException> { fixture.scheduler.onInvalidated() })
            fixture.onHide = null
            fixture.scheduler.refresh()
            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertTrue(fixture.requests.isEmpty())
            fixture.scheduler.stop()
        }

    @Test
    fun `observer failure releases session sources and permits a later restart`() =
        onEdtTest {
            val failures = ArrayList<Throwable>()
            val owner = SupervisorJob(backgroundScope.coroutineContext[Job])
            val scope =
                CoroutineScope(
                    backgroundScope.coroutineContext + owner + CoroutineExceptionHandler { _, failure -> failures += failure },
                )
            val fixture = Fixture(scope)
            fixture.start()
            runCurrent()
            val failure = IllegalStateException("host popup failed")
            fixture.onHide = { throw failure }
            fixture.revisions.value = 1
            runCurrent()

            assertEquals(failure.message, assertIs<IllegalStateException>(failures.single()).message)
            assertEquals(0, fixture.revisions.subscriptionCount.value)
            fixture.onHide = null
            fixture.start()
            runCurrent()
            advanceTimeBy(75.milliseconds)
            runCurrent()
            assertEquals(listOf(snapshot("git s")), fixture.requests)
            fixture.scheduler.stop()
            owner.cancel()
        }

    @Test
    fun `scope cancellation stops delayed requests and releases observations`() =
        onEdtTest {
            val owner = Job(backgroundScope.coroutineContext[Job])
            val fixture = Fixture(CoroutineScope(backgroundScope.coroutineContext + owner))
            fixture.start()
            runCurrent()
            owner.cancel()
            runCurrent()
            advanceTimeBy(100.milliseconds)
            runCurrent()

            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.revisions.subscriptionCount.value)
            fixture.scheduler.stop()
        }

    @Test
    fun `cheap trigger characters bypass the normal length threshold`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope, minimumNonWhitespaceCharacters = 99)
            fixture.start()
            runCurrent()
            for (command in listOf("-", "/", "\\", "$", "=", "go ")) {
                fixture.active = snapshot(command)
                fixture.scheduler.refreshNow()
            }

            assertEquals(listOf("-", "/", "\\", "$", "=", "go "), fixture.requests.map { it.commandText })
            fixture.scheduler.stop()
        }

    @Test
    fun `short ordinary text whitespace and unavailable command lines remain hidden`() =
        onEdtTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            runCurrent()
            for (command in listOf("g", " ", "", null)) {
                fixture.active = command?.let(::snapshot)
                fixture.scheduler.refreshNow()
            }

            assertTrue(fixture.requests.isEmpty())
            fixture.scheduler.stop()
        }

    @Test
    fun `scheduler mutation rejects calls outside the EDT`() {
        val scope = CoroutineScope(Job())
        val fixture = Fixture(scope)
        try {
            assertFailsWith<IllegalStateException> { fixture.start() }
            assertFailsWith<IllegalStateException> { fixture.scheduler.stop() }
            assertFailsWith<IllegalStateException> { fixture.scheduler.refresh() }
            assertFailsWith<IllegalStateException> { fixture.scheduler.onFocusLost() }
            assertFailsWith<IllegalStateException> { fixture.scheduler.onInvalidated() }
        } finally {
            scope.cancel()
        }
    }

    private fun onEdtTest(block: suspend TestScope.() -> Unit) {
        SwingUtilities.invokeAndWait { runTest { block() } }
    }

    private class Fixture(
        scope: CoroutineScope,
        minimumNonWhitespaceCharacters: Int = 2,
    ) {
        val revisions = MutableStateFlow(0L)
        var active: TerminalShellCommandLineSnapshot? = snapshot("git s")
        var focused = true
        var eligible = true
        val requests = ArrayList<TerminalShellCommandLineSnapshot>()
        var feedbackHandler: SwingShellSuggestionFeedbackHandler? = null
        var hideCount = 0
        var onHide: (() -> Unit)? = null
        val scheduler =
            SwingShellSuggestionScheduler(
                observationScope = scope,
                edtDispatcher = UnconfinedTestDispatcher(scope.coroutineContext[TestCoroutineScheduler]),
                isFocused = { focused },
                isEligible = { eligible },
                requestSuggestions = { snapshot, feedback ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    requests += snapshot
                    feedbackHandler = feedback
                },
                hideSuggestions = {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    hideCount++
                    onHide?.invoke()
                },
                minimumNonWhitespaceCharacters = minimumNonWhitespaceCharacters,
            )

        fun start() = scheduler.start({ active }, revisions)
    }

    private companion object {
        fun snapshot(command: String): TerminalShellCommandLineSnapshot =
            TerminalShellCommandLineSnapshot(command, command.length, command.length, cursorRow = 2)

        fun feedback(): SwingShellSuggestionFeedback =
            SwingShellSuggestionFeedback(
                SwingShellSuggestionFeedbackKind.ACCEPTED,
                SwingShellSuggestion("status", 4, 5, "spec", "SUBCOMMAND"),
                0,
                SwingShellSuggestionRequest("git s", 5),
            )
    }
}
