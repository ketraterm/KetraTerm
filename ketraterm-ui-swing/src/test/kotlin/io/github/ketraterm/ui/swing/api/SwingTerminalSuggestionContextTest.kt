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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.session.*
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalSuggestionContextTest {
    @Test
    fun `rendering only inline presentation retires pending automatic requests without claiming keys`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            var opened = 0
            view.setInlineShellSuggestionProvider {
                opened++
                SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git stash"))))
            }
            fixture.settings = fixture.settings.copy { it.inlineShellSuggestionsEnabled = true }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            val expected = assertNotNull(fixture.session.captureCommandEdit())
            assertTrue(view.presentInlineShellSuggestion(expected, "tatus"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, opened)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\t", writes.toString("UTF-8"))
            assertFalse(expected.isCancelled)
        }

    @Test
    fun `configured providers remain manual when automatic requests are disabled`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            var popupOpened = 0
            var inlineOpened = 0
            view.setShellSuggestionProvider {
                popupOpened++
                flowOf(listOf(inlineCandidate("git status")))
            }
            view.setInlineShellSuggestionProvider {
                inlineOpened++
                SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git stash"))))
            }
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, popupOpened)
            assertEquals(0, inlineOpened)
            view.requestActiveShellSuggestions()
            val manual = assertNotNull(view.requestActiveInlineShellSuggestions())
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, popupOpened)
            assertEquals(1, inlineOpened)
            assertTrue(manual.isActive)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git stash", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `popup and inline automatic providers use independent delays and content`(inlineFirst: Boolean) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            var popupOpened = 0
            var inlineOpened = 0
            view.setShellSuggestionProvider {
                popupOpened++
                flowOf(listOf(inlineCandidate("git status")))
            }
            view.setInlineShellSuggestionProvider {
                inlineOpened++
                assertNull(it.selectedPopupCommandText)
                SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git stash --all"))))
            }
            fixture.settings =
                fixture.settings.copy {
                    it.shellSuggestionsEnabled = true
                    it.inlineShellSuggestionsEnabled = true
                    it.shellSuggestionDebounceMillis = if (inlineFirst) 150 else 50
                    it.inlineShellSuggestionDebounceMillis = if (inlineFirst) 50 else 150
                }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(49)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, popupOpened)
            assertEquals(0, inlineOpened)
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()
            assertEquals(if (inlineFirst) 0 else 1, popupOpened)
            assertEquals(if (inlineFirst) 1 else 0, inlineOpened)
            dispatcher.scheduler.advanceTimeBy(99)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, popupOpened + inlineOpened)
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, popupOpened)
            assertEquals(1, inlineOpened)
            assertTrue(view.currentShellSuggestionState().visible)
            // Disabling automatic popups must not retire or restart the independent inline result.
            fixture.settings = fixture.settings.copy { it.shellSuggestionsEnabled = false }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            assertFalse(view.currentShellSuggestionState().visible)
            assertEquals(1, inlineOpened)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git stash --all", writes.toString("UTF-8"))
        }

    @Test
    fun `changing delays reschedules only each surface pending deadline`() =
        withInlineFixture { fixture, dispatcher, _ ->
            val view = fixture.terminal
            var popupOpened = 0
            var inlineOpened = 0
            view.setShellSuggestionProvider {
                popupOpened++
                flowOf(listOf(inlineCandidate("git status")))
            }
            view.setInlineShellSuggestionProvider {
                inlineOpened++
                SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git stash"))))
            }
            fixture.settings =
                fixture.settings.copy {
                    it.shellSuggestionsEnabled = true
                    it.inlineShellSuggestionsEnabled = true
                    it.shellSuggestionDebounceMillis = 200
                    it.inlineShellSuggestionDebounceMillis = 400
                }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(100)
            fixture.settings =
                fixture.settings.copy {
                    it.shellSuggestionDebounceMillis = 20
                    it.inlineShellSuggestionDebounceMillis = 600
                }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(19)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, popupOpened)
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, popupOpened)
            dispatcher.scheduler.advanceTimeBy(579)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, inlineOpened)
            dispatcher.scheduler.advanceTimeBy(1)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, inlineOpened)
        }

    @ParameterizedTest
    @ValueSource(strings = ["disable", "provider", "replace", "unbind", "dispose", "master", "focus", "selection"])
    fun `automatic inline pending work follows its own settings provider and binding`(change: String) =
        withInlineFixture { fixture, dispatcher, _ ->
            val view = fixture.terminal
            var originalOpened = 0
            var replacementOpened = 0
            view.setInlineShellSuggestionProvider {
                originalOpened++
                SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git status"))))
            }
            fixture.settings =
                fixture.settings.copy {
                    it.inlineShellSuggestionsEnabled = true
                    it.inlineShellSuggestionDebounceMillis = 100
                }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(50)
            when (change) {
                "disable" -> {
                    fixture.settings = fixture.settings.copy { it.inlineShellSuggestionsEnabled = false }
                    view.reloadSettings()
                }
                "master" -> {
                    fixture.settings = fixture.settings.copy { it.smartSuggestionsEnabled = false }
                    view.reloadSettings()
                }
                "provider" -> view.setInlineShellSuggestionProvider(null)
                "replace" ->
                    view.setInlineShellSuggestionProvider {
                        replacementOpened++
                        SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git stash"))))
                    }
                "unbind" -> view.unbind()
                "dispose" -> view.dispose()
                "focus" -> view.focusListeners.forEach { it.focusLost(FocusEvent(view, FocusEvent.FOCUS_LOST)) }
                "selection" -> assertTrue(view.selectAll())
            }
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, originalOpened)
            assertEquals(if (change == "replace") 1 else 0, replacementOpened)
            if (change != "replace") assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
        }

    @Test
    fun `manual inline replaces delayed automatic AI and survives automatic settings and provider changes`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val release = CompletableDeferred<Unit>()
            var opened = 0
            var replacementOpened = 0
            view.setInlineShellSuggestionProvider {
                val automatic = ++opened == 1
                SwingShellSuggestionSource(
                    flow {
                        if (automatic) withContext(NonCancellable) { release.await() }
                        emit(listOf(inlineCandidate(if (automatic) "git status" else "git stash")))
                    },
                )
            }
            fixture.settings =
                fixture.settings.copy {
                    it.inlineShellSuggestionsEnabled = true
                    it.inlineShellSuggestionDebounceMillis = 0
                }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            assertEquals(1, opened)
            val manual = assertNotNull(view.requestActiveInlineShellSuggestions())
            dispatcher.scheduler.runCurrent()
            assertEquals(2, opened)
            fixture.settings = fixture.settings.copy { it.inlineShellSuggestionsEnabled = false }
            view.reloadSettings()
            view.setInlineShellSuggestionProvider {
                replacementOpened++
                SwingShellSuggestionSource(flowOf(listOf(inlineCandidate("git show"))))
            }
            release.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(manual.isActive)
            assertEquals(0, replacementOpened)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git stash", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(strings = ["popup-replace", "popup-publish", "inline-replace", "inline-publish"])
    fun `mapped Tab cannot accept a replacement interaction or publication`(change: String) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val inline = change.startsWith("inline")

            fun begin() =
                assertNotNull(
                    if (inline) {
                        view.beginInlineShellSuggestionInteraction()
                    } else {
                        view.beginActiveShellSuggestionInteraction()
                    },
                )
            val original = begin()
            original.publish(listOf(inlineCandidate("git status")), selectedIndex = 0)
            if (!inline) {
                original.select(original.snapshot, 0)
                view.presentShellSuggestions(original)
            }
            var replacement: SwingShellSuggestionInteraction? = null
            val keymap =
                SwingShellSuggestionKeymap { event ->
                    if (event.keyCode != KeyEvent.VK_TAB) {
                        null
                    } else {
                        val next = if (change.endsWith("publish")) original else begin()
                        next.publish(listOf(inlineCandidate("git stash")), selectedIndex = 0)
                        if (!inline) view.presentShellSuggestions(next)
                        replacement = next
                        SwingShellSuggestionAction.ACCEPT_SELECTED
                    }
                }
            if (inline) view.setInlineShellSuggestionKeymap(keymap) else view.setShellSuggestionKeymap(keymap)

            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()

            assertTrue(assertNotNull(replacement).isActive)
            assertEquals("", writes.toString("UTF-8"))
            assertTrue(fixture.feedback.isEmpty())
        }

    @Test
    fun `current inline result retries when the copied frame catches up without reopening its provider`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            fixture.feedHost("\r\u001b[2K    git st")
            fixture.editableSource.value = TerminalShellCommandLineSnapshot("git st", 6, 10, 0)
            dispatcher.scheduler.runCurrent()
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            var opened = 0
            view.requestInlineShellSuggestions(inline) {
                opened++
                SwingShellSuggestionSource(flowOf(listOf(SwingShellSuggestion("git status", 0, 6, "AI", "COMMAND"))))
            }
            dispatcher.scheduler.runCurrent()
            assertTrue(inline.isActive)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())

            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(inline.isActive)
            assertEquals(1, opened)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(6) + "git status", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(strings = ["close", "supersede", "input", "unbind"])
    fun `frame arrival cannot revive obsolete inline work`(end: String) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            fixture.feedHost("\r\u001b[2K    git st")
            fixture.editableSource.value = TerminalShellCommandLineSnapshot("git st", 6, 10, 0)
            dispatcher.scheduler.runCurrent()
            val old = assertNotNull(view.beginInlineShellSuggestionInteraction())
            old.publish(listOf(SwingShellSuggestion("git status", 0, 6, "AI", "COMMAND")), selectedIndex = 0)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            var replacement: SwingShellSuggestionInteraction? = null
            when (end) {
                "close" -> old.close()
                "supersede" -> {
                    replacement = assertNotNull(view.beginInlineShellSuggestionInteraction())
                    replacement.publish(listOf(SwingShellSuggestion("git stash", 0, 6, "AI", "COMMAND")), selectedIndex = 0)
                }
                "input" -> fixture.session.submitBytes("x".toByteArray())
                "unbind" -> view.unbind()
            }

            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(old.isActive)
            if (replacement != null) {
                assertTrue(replacement.isActive)
                assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
                dispatcher.scheduler.runCurrent()
                assertEquals("\u007f".repeat(6) + "git stash", writes.toString("UTF-8"))
            } else {
                assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
                assertEquals(if (end == "input") "x" else "", writes.toString("UTF-8"))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["input", "departure", "prompt"])
    fun `popup continuation ends with external edits even when command text returns unchanged`(change: String) =
        withInlineFixture("cd x") { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(SwingShellSuggestion("xyz/", 3, 4, "paths", "PATH")), selectedIndex = 0)
            popup.select(popup.snapshot, 0)
            view.presentShellSuggestions(popup)
            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()
            fixture.feedHost("\r\u001b[2K    cd xyz/")
            val accepted = TerminalShellCommandLineSnapshot("cd xyz/", 7, 11, 0)
            fixture.editableSource.value = accepted
            dispatcher.scheduler.advanceUntilIdle()
            when (change) {
                "input" -> fixture.session.submitBytes("external".toByteArray())
                "departure" -> fixture.editableSource.value = accepted.copy(commandText = "cd abc/")
                "prompt" -> fixture.editableSource.value = null
            }
            // Even a conflated departure/return must not revive the old browsing context.
            fixture.editableSource.value = accepted
            fixture.feedHost("\r\u001b[2K    cd xyz/")
            dispatcher.scheduler.advanceUntilIdle()
            writes.reset()
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(SwingShellSuggestion("ai/", 7, 7, "AI", "COMMAND")), selectedIndex = 0)

            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("ai/", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(strings = ["unselected", "unique", "preselected", "hovered"])
    fun `Tab reaches the shell when the popup has no deliberate selection`(presentation: String) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            val items =
                if (presentation == "unique") {
                    listOf(inlineCandidate("git status"))
                } else {
                    listOf(inlineCandidate("git status"), inlineCandidate("git stash"))
                }
            popup.publish(items, selectedIndex = if (presentation == "preselected") 0 else -1)
            view.presentShellSuggestions(popup)
            if (presentation == "hovered") fixture.view.listener.onSuggestionHovered(fixture.view.snapshot, 1)
            assertFalse(popup.hasExplicitSelection)

            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()

            assertEquals("\t", writes.toString("UTF-8"))
            assertNotEquals(SwingShellSuggestionCloseReason.ACCEPTED, popup.closeReason)
            assertTrue(fixture.feedback.isEmpty())
        }

    @Test
    fun `rendering-only suffix claims no acceptance keys`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val expected = assertNotNull(fixture.session.captureCommandEdit())
            assertTrue(fixture.terminal.presentInlineShellSuggestion(expected, "tatus"))
            val key = KeyEvent(fixture.terminal, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED)
            fixture.terminal.keyListeners.forEach { it.keyPressed(key) }
            dispatcher.scheduler.runCurrent()
            assertEquals("\t", writes.toString("UTF-8"))
            assertFalse(expected.isCancelled)
        }

    @Test
    fun `rendering-only takeover preserves a managed interaction started by its close listener`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val first = assertNotNull(view.beginInlineShellSuggestionInteraction())
            val expected = assertNotNull(fixture.session.captureCommandEdit())
            var newest: SwingShellSuggestionInteraction? = null
            first.addChangeListener {
                if (it.isClosed) {
                    newest =
                        assertNotNull(view.beginInlineShellSuggestionInteraction()).also { replacement ->
                            replacement.publish(listOf(inlineCandidate("git stash --all")), selectedIndex = 0)
                        }
                }
            }

            assertFalse(view.presentInlineShellSuggestion(expected, "tatus"))
            assertTrue(assertNotNull(newest).isActive)
            assertFalse(expected.isCancelled)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git stash --all", writes.toString("UTF-8"))
        }

    @Test
    fun `rendering-only takeover preserves a newer rendering-only presentation after reentry`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val first = assertNotNull(view.beginInlineShellSuggestionInteraction())
            val older = assertNotNull(fixture.session.captureCommandEdit())
            val newer = assertNotNull(fixture.session.captureCommandEdit())
            first.addChangeListener { if (it.isClosed) assertTrue(view.presentInlineShellSuggestion(newer, "how")) }

            assertFalse(view.presentInlineShellSuggestion(older, "tatus"))
            assertFalse(older.isCancelled)
            assertFalse(newer.isCancelled)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\t", writes.toString("UTF-8"))
        }

    @Test
    fun `reentrant inline replacement keeps the newest interaction`() =
        withInlineFixture { fixture, _, _ ->
            val view = fixture.terminal
            val first = assertNotNull(view.beginInlineShellSuggestionInteraction())
            var newest: SwingShellSuggestionInteraction? = null
            first.addChangeListener { if (it.isClosed) newest = view.beginInlineShellSuggestionInteraction() }
            assertNull(view.beginInlineShellSuggestionInteraction())
            assertTrue(assertNotNull(newest).isActive)
            assertTrue(assertNotNull(newest).publish(listOf(inlineCandidate("git status")), selectedIndex = 0))
        }

    @ParameterizedTest
    @ValueSource(strings = ["unbind", "dispose", "rebind"])
    fun `binding cleanup rejects reentrant inline requests`(change: String) =
        withInlineFixture { fixture, _, writes ->
            val view = fixture.terminal
            val first = assertNotNull(view.beginInlineShellSuggestionInteraction())
            var closed = false
            first.addChangeListener {
                if (it.isClosed) {
                    closed = true
                    assertNull(view.beginInlineShellSuggestionInteraction())
                }
            }
            when (change) {
                "unbind" -> view.unbind()
                "dispose" -> view.dispose()
                "rebind" -> view.bind(fixture.session)
            }
            assertTrue(closed)
            assertFalse(first.isActive)
            assertEquals("", writes.toString("UTF-8"))
        }

    @Test
    fun `independent inline result obeys popup compatibility and guarded whole acceptance`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            view.setInlineShellSuggestionCoexistence(SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION)
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(inlineCandidate("git status --short")), selectedIndex = 0)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status"), inlineCandidate("git stash")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            assertTrue(inline.isActive)
            popup.publish(listOf(inlineCandidate("git status"), inlineCandidate("git stash")), selectedIndex = 1, preserveSelection = false)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            assertTrue(inline.isActive)
            assertTrue(popup.isActive)
            view.setInlineShellSuggestionCoexistence(SwingInlineShellSuggestionCoexistence.INDEPENDENT)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git status --short", writes.toString("UTF-8"))
            assertFalse(popup.isActive)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `removeNotify completes peer cleanup when inline close listeners throw`(cancelled: Boolean) =
        withInlineFixture { fixture, _, _ ->
            val view = fixture.terminal
            val failure = if (cancelled) CancellationException("close cancelled") else IllegalStateException("close failed")
            view.addNotify()
            try {
                assertTrue(view.isDisplayable)
                view.focusListeners.forEach { it.focusGained(FocusEvent(view, FocusEvent.FOCUS_GAINED)) }
                val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
                inline.addChangeListener { if (it.isClosed) throw failure }
                assertSame(failure, assertFailsWith<RuntimeException> { view.removeNotify() })
                assertFalse(view.isDisplayable)
                assertTrue(view.isCoroutineScopeActive)
                assertFalse(fixture.session.isClosed)
            } finally {
                if (view.isDisplayable) view.removeNotify()
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `rebinding completes when inline close listeners throw`(cancelled: Boolean) =
        withInlineFixture { fixture, _, _ ->
            val view = fixture.terminal
            val failure = if (cancelled) CancellationException("close cancelled") else IllegalStateException("close failed")
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.addChangeListener { if (it.isClosed) throw failure }
            var completed = false
            assertSame(
                failure,
                assertFailsWith<RuntimeException> {
                    view.bind(fixture.replacementSession) {
                        assertNull(it)
                        completed = true
                    }
                },
            )
            fixture.mutationDispatcher.scheduler.runCurrent()
            assertTrue(completed)
            assertFalse(fixture.session.isClosed)
            assertFalse(fixture.replacementSession.isClosed)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `settings finish applying when inline close listeners throw`(cancelled: Boolean) =
        withInlineFixture { fixture, _, _ ->
            val view = fixture.terminal
            val failure = if (cancelled) CancellationException("close cancelled") else IllegalStateException("close failed")
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.addChangeListener { if (it.isClosed) throw failure }
            val previousSize = view.preferredSize
            fixture.settings =
                fixture.settings.copy {
                    it.smartSuggestionsEnabled = false
                    it.font = it.font.deriveFont(it.font.size2D + 3f)
                }
            assertSame(failure, assertFailsWith<RuntimeException> { view.reloadSettings() })
            assertEquals(fixture.settings.font, view.font)
            assertNotEquals(previousSize, view.preferredSize)
            assertEquals(view.preferredGridSize(fixture.settings.columns, fixture.settings.rows), view.preferredSize)
            assertNull(view.beginInlineShellSuggestionInteraction())
            view.reloadSettings()
            assertEquals(fixture.settings.font, view.font)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `selection cancels pending inline work before any result exists`(lateCompletion: Boolean) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val release = CompletableDeferred<Unit>()
            var started = false
            var finished = false
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(
                inline,
            ) {
                SwingShellSuggestionSource(
                    flow {
                        started = true
                        try {
                            if (lateCompletion) withContext(NonCancellable) { release.await() } else release.await()
                            emit(listOf(inlineCandidate("git status")))
                        } finally {
                            finished = true
                        }
                    },
                )
            }
            try {
                dispatcher.scheduler.runCurrent()
                assertTrue(started)
                assertTrue(view.selectAll())
                assertTrue(inline.isClosed)
                dispatcher.scheduler.runCurrent()
                if (!lateCompletion) assertTrue(finished, "Selection must cancel a suspended source before it produces a result")
                view.clearSelection()
                release.complete(Unit)
                dispatcher.scheduler.runCurrent()
                assertTrue(finished)
                assertFalse(inline.publish(listOf(inlineCandidate("git status")), selectedIndex = 0))
                assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
                assertEquals("", writes.toString("UTF-8"))
            } finally {
                release.complete(Unit)
                dispatcher.scheduler.runCurrent()
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `Tab accepts visible inline text with or without a popup`(dismissPopupFirst: Boolean) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(inlineCandidate("git status --short")), selectedIndex = 0)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")), selectedIndex = if (dismissPopupFirst) 0 else -1)
            view.presentShellSuggestions(popup)

            fun press(key: Int) {
                val event = KeyEvent(view, KeyEvent.KEY_PRESSED, 0L, 0, key, KeyEvent.CHAR_UNDEFINED)
                view.keyListeners.forEach { it.keyPressed(event) }
                assertTrue(event.isConsumed)
                val released = KeyEvent(view, KeyEvent.KEY_RELEASED, 0L, 0, key, KeyEvent.CHAR_UNDEFINED)
                view.keyListeners.forEach { it.keyReleased(released) }
            }
            if (dismissPopupFirst) {
                press(KeyEvent.VK_ESCAPE)
                assertEquals(SwingShellSuggestionCloseReason.DISMISSED, popup.closeReason)
                assertTrue(inline.isActive)
            }
            press(KeyEvent.VK_TAB)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git status --short", writes.toString("UTF-8"))
        }

    @Test
    fun `Tab reaches the shell when incompatible inline text is hidden and popup selection is passive`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            view.setInlineShellSuggestionCoexistence(SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION)
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(inlineCandidate("git status --short")), selectedIndex = 0)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git stash")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            val tab = KeyEvent(view, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED)
            view.keyListeners.forEach { it.keyPressed(tab) }
            assertTrue(tab.isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\t", writes.toString("UTF-8"))
        }

    @Test
    fun `stale inline Tab does not select a popup item or forward Tab`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(inlineCandidate("git status --short")), selectedIndex = 0)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")))
            view.presentShellSuggestions(popup)
            view.setInlineShellSuggestionKeymap {
                fixture.session.submitBytes("x".toByteArray())
                SwingShellSuggestionAction.ACCEPT_SELECTED
            }
            val tab = KeyEvent(view, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED)
            view.keyListeners.forEach { it.keyPressed(tab) }
            assertTrue(tab.isConsumed)
            assertEquals(-1, popup.snapshot.selectedIndex)
            dispatcher.scheduler.runCurrent()
            assertEquals("x", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(strings = ["open", "collect", "cancel"])
    fun `inline provider failures close only the inline slot`(where: String) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val failures = ArrayList<Exception>()
            view.setShellSuggestionFailureHandler { _, error -> failures += error }
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(
                inline,
            ) {
                if (where == "open") throw IllegalStateException("open failed")
                SwingShellSuggestionSource(
                    flow {
                        if (where == "cancel") throw CancellationException("source cancelled")
                        throw IllegalStateException("collection failed")
                    },
                )
            }
            dispatcher.scheduler.runCurrent()
            assertTrue(inline.isClosed)
            assertTrue(popup.isActive)
            assertEquals(if (where == "cancel") 0 else 1, failures.size)
            assertEquals("", writes.toString("UTF-8"))
        }

    @Test
    fun `custom inline keys retain source feedback`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(
                inline,
            ) {
                SwingShellSuggestionSource(
                    flowOf(listOf(inlineCandidate("git status"))),
                    feedbackHandler = { feedback += it },
                )
            }
            dispatcher.scheduler.runCurrent()
            view.setInlineShellSuggestionKeymap { event ->
                if (event.keyCode == KeyEvent.VK_RIGHT) SwingShellSuggestionAction.ACCEPT_SELECTED else null
            }
            val key = KeyEvent(view, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED)
            view.keyListeners.forEach { it.keyPressed(key) }
            assertTrue(key.isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git status", writes.toString("UTF-8"))
            assertEquals(listOf(SwingShellSuggestionFeedbackKind.ACCEPTED), feedback.map { it.kind })
        }

    @Test
    fun `source refresh retires the previous inline publication before new results arrive`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            view.setInlineShellSuggestionCoexistence(SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION)
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(
                inline,
            ) { request ->
                SwingShellSuggestionSource(
                    flow {
                        if (request.selectedPopupCommandText != null) awaitCancellation()
                        emit(listOf(inlineCandidate("git status --short")))
                    },
                )
            }
            dispatcher.scheduler.runCurrent()
            val previous = inline.snapshot
            assertNotNull(previous.selectedSuggestion)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            view.setInlineShellSuggestionCoexistence(SwingInlineShellSuggestionCoexistence.INDEPENDENT)
            assertTrue(inline.snapshot.suggestions.isEmpty())
            assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, inline.tryAccept(previous, 0))
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertTrue(inline.isActive)
            assertEquals("", writes.toString("UTF-8"))
        }

    @Test
    fun `inline provider refreshes on popup selection and cancels only its own work`() =
        withInlineFixture { fixture, dispatcher, _ ->
            val view = fixture.terminal
            view.setInlineShellSuggestionCoexistence(SwingInlineShellSuggestionCoexistence.EXTEND_POPUP_SELECTION)
            val requests = ArrayList<SwingInlineShellSuggestionRequest>()
            var cancelled = 0
            val feedback = ArrayList<SwingShellSuggestionFeedback>()
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            val provider =
                SwingInlineShellSuggestionProvider { request ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    requests += request
                    SwingShellSuggestionSource(
                        flow {
                            try {
                                emit(listOf(inlineCandidate((request.selectedPopupCommandText ?: "git status") + " --verbose")))
                                awaitCancellation()
                            } finally {
                                cancelled++
                            }
                        },
                        feedbackHandler = { feedback += it },
                    )
                }
            view.requestInlineShellSuggestions(inline, provider)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(null), requests.map { it.selectedPopupCommandText })
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status"), inlineCandidate("git stash")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            dispatcher.scheduler.runCurrent()
            popup.select(popup.snapshot, 1)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(null, "git status", "git stash"), requests.map { it.selectedPopupCommandText })
            assertEquals(2, cancelled)
            assertTrue(inline.isActive)
            assertFalse(view.dismissInlineShellSuggestion(), "Deliberate popup selection hides inline presentation")
            inline.close()
            dispatcher.scheduler.runCurrent()
            assertEquals(3, cancelled)
            assertTrue(popup.isActive)
            assertTrue(feedback.isEmpty())
        }

    @Test
    fun `late independent AI cannot override a deliberately selected popup item`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val release = CompletableDeferred<Unit>()
            var opened = 0
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(inline) { request ->
                opened++
                assertNull(request.selectedPopupCommandText)
                SwingShellSuggestionSource(
                    flow {
                        release.await()
                        emit(listOf(inlineCandidate("git stash --all")))
                    },
                )
            }
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            assertFalse(popup.hasExplicitSelection)
            popup.select(popup.snapshot, 0)
            assertTrue(popup.hasExplicitSelection)
            release.complete(Unit)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, opened)
            assertTrue(inline.isActive)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + "git status", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(strings = ["enter", "tab", "deeper"])
    fun `directory completion resets selection and keeps late AI out of continued browsing`(nextAction: String) =
        withInlineFixture("cd x") { fixture, dispatcher, writes ->
            val view = fixture.terminal

            fun press(code: Int) {
                assertTrue(fixture.press(code).isConsumed)
                val up = KeyEvent(view, KeyEvent.KEY_RELEASED, 0L, 0, code, KeyEvent.CHAR_UNDEFINED)
                view.keyListeners.forEach { it.keyReleased(up) }
                dispatcher.scheduler.runCurrent()
            }

            fun echo(command: String) {
                fixture.feedHost("\r\u001b[2K    $command")
                fixture.editableSource.value = TerminalShellCommandLineSnapshot(command, command.length, command.length + 4, 0)
                dispatcher.scheduler.advanceUntilIdle()
            }

            fun popup(
                text: String,
                start: Int,
                end: Int,
            ): SwingShellSuggestionInteraction {
                val interaction = assertNotNull(view.beginActiveShellSuggestionInteraction())
                interaction.publish(listOf(SwingShellSuggestion(text, start, end, "paths", "PATH")))
                view.presentShellSuggestions(interaction)
                return interaction
            }
            popup("xyz/", 3, 4)
            press(KeyEvent.VK_DOWN)
            press(KeyEvent.VK_TAB)
            echo("cd xyz/")
            val child = popup("abc/", 7, 7)
            val release = CompletableDeferred<Unit>()
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(inline) {
                SwingShellSuggestionSource(
                    flow {
                        release.await()
                        emit(listOf(SwingShellSuggestion("ai/", 7, 7, "AI", "COMMAND")))
                    },
                )
            }
            release.complete(Unit)
            dispatcher.scheduler.runCurrent()
            assertFalse(child.hasExplicitSelection)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            if (nextAction == "deeper") {
                press(KeyEvent.VK_DOWN)
                press(KeyEvent.VK_TAB)
                echo("cd xyz/abc/")
                popup("def/", 11, 11)
            }
            press(if (nextAction == "tab") KeyEvent.VK_TAB else KeyEvent.VK_ENTER)
            val expected = "\u007fxyz/" + (if (nextAction == "deeper") "abc/" else "") + (if (nextAction == "tab") "\t" else "\r")
            assertEquals(expected, writes.toString("UTF-8"))
        }

    @Test
    fun `stale inline publication and late provider completion cannot revive a superseded request`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val release = CompletableDeferred<Unit>()
            val first = assertNotNull(view.beginInlineShellSuggestionInteraction())
            view.requestInlineShellSuggestions(
                first,
            ) {
                SwingShellSuggestionSource(
                    flow {
                        withContext(NonCancellable) { release.await() }
                        emit(listOf(inlineCandidate("git status")))
                    },
                )
            }
            dispatcher.scheduler.runCurrent()
            val next = assertNotNull(view.beginInlineShellSuggestionInteraction())
            next.publish(listOf(inlineCandidate("git stash")), selectedIndex = 0)
            release.complete(Unit)
            dispatcher.scheduler.runCurrent()
            assertEquals(SwingShellSuggestionCloseReason.SUPERSEDED, first.closeReason)
            assertFalse(first.publish(listOf(inlineCandidate("git status")), selectedIndex = 0))
            assertTrue(next.isActive)
            fixture.editableSource.value = snapshot().copy(commandText = "changed", cursorOffset = 7, cursorColumn = 11)
            dispatcher.scheduler.runCurrent()
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            assertEquals("", writes.toString("UTF-8"))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `custom overlapping bindings follow deliberate popup intent`(choosePopup: Boolean) =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            view.setShellSuggestionKeymap { if (it.keyCode == KeyEvent.VK_F2) SwingShellSuggestionAction.ACCEPT else null }
            view.setInlineShellSuggestionKeymap { if (it.keyCode == KeyEvent.VK_F2) SwingShellSuggestionAction.ACCEPT_SELECTED else null }
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(inlineCandidate("git stash --all")), selectedIndex = 0)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")), selectedIndex = 0)
            view.presentShellSuggestions(popup)
            if (choosePopup) popup.select(popup.snapshot, 0)
            assertTrue(fixture.press(KeyEvent.VK_F2).isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007f".repeat(5) + if (choosePopup) "git status" else "git stash --all", writes.toString("UTF-8"))
        }

    @Test
    fun `reranking away a popup choice neither arms Enter nor hands Tab to late inline text`() =
        withInlineFixture { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(inlineCandidate("git stash --all")), selectedIndex = 0)
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(inlineCandidate("git status")))
            view.presentShellSuggestions(popup)
            popup.select(popup.snapshot, 0)
            popup.publish(listOf(inlineCandidate("git show")), selectedIndex = 0)
            assertFalse(popup.hasExplicitSelection)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            assertTrue(fixture.press(KeyEvent.VK_ENTER).isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\r", writes.toString("UTF-8"))
        }

    @Test
    fun `explicitly hiding the popup releases inline suppression after directory acceptance`() =
        withInlineFixture("cd x") { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(SwingShellSuggestion("xyz/", 3, 4, "paths", "PATH")))
            view.presentShellSuggestions(popup)
            popup.select(popup.snapshot, 0)
            assertTrue(fixture.press(KeyEvent.VK_TAB).isConsumed)
            dispatcher.scheduler.runCurrent()
            fixture.feedHost("\r\u001b[2K    cd xyz/")
            fixture.editableSource.value = TerminalShellCommandLineSnapshot("cd xyz/", 7, 11, 0)
            dispatcher.scheduler.advanceUntilIdle()
            val child = assertNotNull(view.beginActiveShellSuggestionInteraction())
            child.publish(listOf(SwingShellSuggestion("abc/", 7, 7, "paths", "PATH")))
            view.presentShellSuggestions(child)
            val inline = assertNotNull(view.beginInlineShellSuggestionInteraction())
            inline.publish(listOf(SwingShellSuggestion("ai/", 7, 7, "AI", "COMMAND")), selectedIndex = 0)
            assertTrue(inline.isActive)
            assertEquals(SwingShellSuggestionAcceptanceResult.UNAVAILABLE, view.acceptInlineShellSuggestion())
            view.hideShellSuggestions()
            dispatcher.scheduler.runCurrent()
            assertFalse(child.isActive)
            assertTrue(inline.isActive)
            assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, view.acceptInlineShellSuggestion())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007fxyz/ai/", writes.toString("UTF-8"))
        }

    @Test
    fun `Enter inserts a deliberate directory choice and a second Enter executes without accepting its child`() =
        withInlineFixture("cd x") { fixture, dispatcher, writes ->
            val view = fixture.terminal
            val popup = assertNotNull(view.beginActiveShellSuggestionInteraction())
            popup.publish(listOf(SwingShellSuggestion("xyz/", 3, 4, "paths", "PATH")))
            view.presentShellSuggestions(popup)
            popup.select(popup.snapshot, 0)
            assertTrue(fixture.press(KeyEvent.VK_ENTER).isConsumed)
            val up = KeyEvent(view, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED)
            view.keyListeners.forEach { it.keyReleased(up) }
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007fxyz/", writes.toString("UTF-8"))
            fixture.feedHost("\r\u001b[2K    cd xyz/")
            fixture.editableSource.value = TerminalShellCommandLineSnapshot("cd xyz/", 7, 11, 0)
            dispatcher.scheduler.advanceUntilIdle()
            val child = assertNotNull(view.beginActiveShellSuggestionInteraction())
            child.publish(listOf(SwingShellSuggestion("abc/", 7, 7, "paths", "PATH")), selectedIndex = 0)
            view.presentShellSuggestions(child)
            assertFalse(child.hasExplicitSelection)
            assertTrue(fixture.press(KeyEvent.VK_ENTER).isConsumed)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u007fxyz/\r", writes.toString("UTF-8"))
        }

    private fun inlineCandidate(command: String) = SwingShellSuggestion(command, 0, 5, "test", "COMMAND")

    private fun withInlineFixture(
        command: String = "git s",
        block: (Fixture, kotlinx.coroutines.test.TestDispatcher, java.io.ByteArrayOutputStream) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher()
        val writes = java.io.ByteArrayOutputStream()
        lateinit var ingress: TerminalConnectorListener
        val connector =
            object : TerminalConnector by NoOpConnector {
                override fun start(listener: TerminalConnectorListener) {
                    ingress = listener
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    writes.write(bytes, offset, length)
                }
            }
        Fixture(
            sessionHandler = true,
            connector = connector,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            suggestionDispatcher = dispatcher,
            uiDispatcher = TerminalUiDispatcher { it.run() },
        ).use { fixture ->
            onEdt {
                fixture.session.start(30, 4)
                fixture.feedHost = { text ->
                    val bytes = text.toByteArray()
                    ingress.onBytes(bytes, 0, bytes.size)
                }
                fixture.editableSource.value = TerminalShellCommandLineSnapshot(command, command.length, 4 + command.length, 0)
                val bytes = "    $command".toByteArray()
                ingress.onBytes(bytes, 0, bytes.size)
                fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                dispatcher.scheduler.runCurrent()
                block(fixture, dispatcher, writes)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["clear", "focus", "input", "unbind", "dispose", "model", "selection", "composition"])
    fun `inline presentation disappears without changing terminal text`(change: String) {
        val dispatcher = StandardTestDispatcher()
        lateinit var ingress: TerminalConnectorListener
        val connector =
            object : TerminalConnector by NoOpConnector {
                override fun start(listener: TerminalConnectorListener) {
                    ingress = listener
                }
            }
        Fixture(
            sessionHandler = true,
            connector = connector,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            uiDispatcher = TerminalUiDispatcher { it.run() },
        ).use { fixture ->
            onEdt {
                fixture.session.start(30, 4)
                val bytes = "    git s".toByteArray()
                ingress.onBytes(bytes, 0, bytes.size)
                fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                dispatcher.scheduler.runCurrent()
                val expected = assertNotNull(fixture.session.captureCommandEdit())

                val before = pixels(fixture.terminal)
                assertTrue(fixture.terminal.presentInlineShellSuggestion(expected, "tatus"))
                assertFalse(before.contentEquals(pixels(fixture.terminal)))
                when (change) {
                    "clear" -> fixture.terminal.clearInlineShellSuggestion()
                    "focus" -> fixture.terminal.focusListeners.forEach { it.focusLost(FocusEvent(fixture.terminal, FocusEvent.FOCUS_LOST)) }
                    "input" ->
                        fixture.terminal.keyListeners.forEach {
                            it.keyTyped(KeyEvent(fixture.terminal, KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, 'x'))
                        }
                    "unbind" -> fixture.terminal.unbind()
                    "dispose" -> fixture.terminal.dispose()
                    "model" -> fixture.editableSource.value = snapshot().copy(commandText = "git st", cursorOffset = 6, cursorColumn = 10)
                    "selection" -> {
                        assertTrue(fixture.terminal.selectAll())
                        assertFalse(
                            fixture.terminal
                                .selectedText()
                                .orEmpty()
                                .contains("tatus"),
                        )
                    }
                    "composition" -> {
                        val text = java.text.AttributedString("x").iterator
                        val event =
                            java.awt.event.InputMethodEvent(
                                fixture.terminal,
                                java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                                text,
                                0,
                                null,
                                null,
                            )
                        fixture.terminal.inputMethodListeners.forEach { it.inputMethodTextChanged(event) }
                    }
                }
                dispatcher.scheduler.runCurrent()
                val after = pixels(fixture.terminal)
                fixture.terminal.clearInlineShellSuggestion()
                assertContentEquals(after, pixels(fixture.terminal), "preview survived $change")
                assertFalse(expected.isCancelled && change == "clear", "clearing presentation cancelled caller authority")
                fixture.session.readRenderFrame { frame ->
                    val codes = IntArray(frame.columns)
                    frame.copyTextLine(0, codes, flags = IntArray(frame.columns))
                    assertEquals('s'.code, codes[8])
                    assertEquals(0, codes[9])
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["bytes", "key", "batch"])
    fun `external input hides inline text before a blocked writer retires`(kind: String) {
        val dispatcher = StandardTestDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var ingress: TerminalConnectorListener
        val connector =
            object : TerminalConnector by NoOpConnector {
                override fun start(listener: TerminalConnectorListener) {
                    ingress = listener
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    entered.countDown()
                    release.await()
                }
            }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            Fixture(sessionHandler = true, connector = connector, workerDispatcher = dispatcher, ioDispatcher = io).use { fixture ->
                try {
                    onEdt {
                        fixture.session.start(30, 4)
                        val bytes = "    git s".toByteArray()
                        ingress.onBytes(bytes, 0, bytes.size)
                        fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                        dispatcher.scheduler.runCurrent()
                        val expected = assertNotNull(fixture.session.captureCommandEdit())
                        val before = pixels(fixture.terminal)
                        assertTrue(fixture.terminal.presentInlineShellSuggestion(expected, "tatus"))
                        dispatcher.scheduler.runCurrent()
                        assertFalse(before.contentEquals(pixels(fixture.terminal)))
                        val key = TerminalKeyEvent.text("x")
                        val admission =
                            when (kind) {
                                "bytes" -> fixture.session.submitBytes(byteArrayOf('x'.code.toByte()))
                                "key" -> fixture.session.submitInput(key)
                                else -> fixture.session.submitInput(expected, listOf(key))
                            }
                        assertEquals(TerminalInputAdmission.ACCEPTED, admission)
                        assertTrue(entered.await(5, TimeUnit.SECONDS), "writer did not enter connector.write")
                        assertFalse(fixture.session.isCommandEditCurrent(expected))
                        dispatcher.scheduler.runCurrent()
                        assertContentEquals(before, pixels(fixture.terminal), "stale ghost remained while $kind input was blocked")
                    }
                } finally {
                    release.countDown()
                }
            }
        }
    }

    private fun pixels(view: SwingTerminal): IntArray {
        val image = java.awt.image.BufferedImage(view.width, view.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            view.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    @Test
    fun `automatic popup stays mounted through shell echo debounce and a delayed provider`() {
        val dispatcher = StandardTestDispatcher()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        Fixture(
            settings = SwingSettings.create { it.smartSuggestionsEnabled = true },
            sessionHandler = true,
            suggestionDispatcher = dispatcher,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            provider = { request ->
                flow {
                    if (request.commandText == "git st") {
                        started.complete(Unit)
                        release.await()
                    }
                    emit(listOf(suggestion(request)))
                }
            },
        ).use { fixture ->
            try {
                onEdt {
                    fixture.session.start(30, 4)
                    fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                    dispatcher.scheduler.runCurrent()
                    dispatcher.scheduler.advanceTimeBy(75)
                    dispatcher.scheduler.runCurrent()
                }
                assertEquals("git s", fixture.awaitRequest().commandText)
                fixture.view.awaitVisible()
                val visibility = onEdt { fixture.view.visibilityChanges.toList() }
                onEdt {
                    val typed = KeyEvent(fixture.terminal, KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, 't')
                    fixture.terminal.keyListeners.forEach { it.keyTyped(typed) }
                    assertTrue(fixture.terminal.currentShellSuggestionState().visible)
                    fixture.editableSource.value = snapshot().copy(commandText = "git st", cursorOffset = 6, cursorColumn = 6)
                    dispatcher.scheduler.runCurrent()
                    assertTrue(fixture.terminal.currentShellSuggestionState().visible)
                    dispatcher.scheduler.advanceTimeBy(75)
                    dispatcher.scheduler.runCurrent()
                }
                assertEquals("git st", fixture.awaitRequest().commandText)
                started.awaitCompletion()
                onEdt {
                    assertEquals(visibility, fixture.view.visibilityChanges)
                    assertTrue(fixture.terminal.currentShellSuggestionState().visible)
                }
                release.complete(Unit)
                onEdt { dispatcher.scheduler.runCurrent() }
                fixture.view.awaitVisible()
                onEdt {
                    assertEquals(visibility, fixture.view.visibilityChanges)
                    assertEquals(
                        6,
                        fixture.view.snapshot.visibleSuggestions
                            .single()
                            .replacementEndOffset,
                    )
                }
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `rapid deletion followed by Backspace on an empty prompt closes the retained popup`() {
        val dispatcher = StandardTestDispatcher()
        Fixture(
            settings = SwingSettings.create { it.smartSuggestionsEnabled = true },
            sessionHandler = true,
            suggestionDispatcher = dispatcher,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        ).use { fixture ->
            onEdt {
                fixture.session.start(30, 4)
                fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                dispatcher.scheduler.runCurrent()
                dispatcher.scheduler.advanceTimeBy(75)
                dispatcher.scheduler.runCurrent()
            }
            assertEquals("git s", fixture.awaitRequest().commandText)
            fixture.view.awaitVisible()
            onEdt {
                assertTrue(fixture.terminal.currentShellSuggestionState().visible)
                fixture.press(KeyEvent.VK_BACK_SPACE)
                fixture.editableSource.value = snapshot().copy(commandText = "", cursorOffset = 0, cursorColumn = 0)
                dispatcher.scheduler.runCurrent()
            }
            onEdt {
                dispatcher.scheduler.runCurrent()
                assertEquals("", fixture.session.activeShellCommandLine()?.commandText)
                fixture.press(KeyEvent.VK_BACK_SPACE)
                dispatcher.scheduler.advanceTimeBy(150)
                dispatcher.scheduler.runCurrent()

                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                assertFalse(fixture.view.component.isVisible)
                assertTrue(fixture.requests.isEmpty())
                assertTrue(fixture.accepted.isEmpty())
            }
        }
    }

    @Test
    fun `automatic typing retains rows and selection until a fresh publication without admitting stale actions`() {
        val dispatcher = StandardTestDispatcher()
        Fixture(
            settings = SwingSettings.create { it.smartSuggestionsEnabled = true },
            suggestionDispatcher = dispatcher,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        ).use { fixture ->
            fixture.session.start(30, 4)
            onEdt {
                fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                val first =
                    fixture.terminal.beginActiveShellSuggestionInteraction(
                        SwingShellSuggestionTrigger.AUTOMATIC,
                        editTarget = fixture.editTarget,
                    ) ?: error("Initial capture failed")
                fixture.terminal.presentShellSuggestions(first)
                first.publish(listOf(suggestion(first.request)), selectedIndex = 0)
                val painted = fixture.view.snapshot
                val visibility = fixture.view.visibilityChanges.toList()

                val typed = KeyEvent(fixture.terminal, KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, 't')
                fixture.terminal.keyListeners.forEach { it.keyTyped(typed) }
                assertTrue(first.isClosed)
                assertTrue(fixture.terminal.currentShellSuggestionState().visible)
                assertSame(painted, fixture.view.snapshot)
                fixture.terminal.doLayout()
                assertTrue(fixture.view.component.width > 0)
                fixture.view.listener.onSuggestionClicked(painted, 0)
                assertTrue(fixture.accepted.isEmpty())
                assertTrue(fixture.feedback.isEmpty())

                fixture.source.value = snapshot().copy(commandText = "git st", cursorOffset = 6, cursorColumn = 6)
                val next =
                    fixture.terminal.beginActiveShellSuggestionInteraction(
                        SwingShellSuggestionTrigger.AUTOMATIC,
                        editTarget = fixture.editTarget,
                    ) ?: error("Updated capture failed")
                fixture.terminal.presentShellSuggestions(next)
                assertSame(painted, fixture.view.snapshot, "An unpublished request must keep the previous rows")
                fixture.view.listener.onSuggestionClicked(painted, 0)
                assertTrue(fixture.accepted.isEmpty())
                next.publish(listOf(suggestion(next.request)))
                assertEquals(0, next.snapshot.selectedIndex, "The selected replacement outcome survives typing")
                assertEquals(visibility, fixture.view.visibilityChanges, "Refreshing must never toggle popup visibility")
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertEquals(listOf(next.request), fixture.accepted.map { it.request })
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["empty", "escape", "enter", "tab", "shortcut", "navigation", "focus", "settings", "provider", "unbind", "failure"],
    )
    fun `retained automatic rows close when refresh ends or becomes ineligible`(end: String) {
        val dispatcher = StandardTestDispatcher()
        Fixture(
            settings = SwingSettings.create { it.smartSuggestionsEnabled = true },
            suggestionDispatcher = dispatcher,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        ).use { fixture ->
            fixture.session.start(30, 4)
            onEdt {
                fixture.terminal.focusListeners.forEach { it.focusGained(FocusEvent(fixture.terminal, FocusEvent.FOCUS_GAINED)) }
                val first =
                    fixture.terminal.beginActiveShellSuggestionInteraction(
                        SwingShellSuggestionTrigger.AUTOMATIC,
                        editTarget = fixture.editTarget,
                    ) ?: error("Initial capture failed")
                fixture.terminal.presentShellSuggestions(first)
                first.publish(listOf(suggestion(first.request)), selectedIndex = 0)
                fixture.press(KeyEvent.VK_BACK_SPACE)
                assertTrue(fixture.terminal.currentShellSuggestionState().visible)
                when (end) {
                    "empty", "failure" -> {
                        val next =
                            fixture.terminal.beginActiveShellSuggestionInteraction(
                                SwingShellSuggestionTrigger.AUTOMATIC,
                                editTarget = fixture.editTarget,
                            ) ?: error("Updated capture failed")
                        fixture.terminal.presentShellSuggestions(next)
                        if (end == "empty") next.publish(emptyList()) else next.close(SwingShellSuggestionCloseReason.FAILED)
                    }
                    "escape" -> fixture.press(KeyEvent.VK_ESCAPE)
                    "enter" -> fixture.press(KeyEvent.VK_ENTER)
                    "tab" -> fixture.press(KeyEvent.VK_TAB)
                    "shortcut" -> fixture.press(KeyEvent.VK_BACK_SPACE, InputEvent.CTRL_DOWN_MASK)
                    "navigation" -> fixture.press(KeyEvent.VK_LEFT)
                    "focus" -> fixture.terminal.focusListeners.forEach { it.focusLost(FocusEvent(fixture.terminal, FocusEvent.FOCUS_LOST)) }
                    "settings" -> {
                        fixture.settings = fixture.settings.copy { it.shellSuggestionsEnabled = false }
                        fixture.terminal.reloadSettings()
                    }
                    "provider" -> fixture.terminal.setShellSuggestionProvider(null)
                    "unbind" -> fixture.terminal.unbind()
                }
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                assertFalse(fixture.view.component.isVisible)
                assertTrue(fixture.accepted.isEmpty())
                assertTrue(fixture.feedback.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["fresh", "metadata", "aba", "close", "span"])
    fun `session handler reports acceptance only for a valid admitted edit`(change: String) =
        runTest {
            val output = java.io.ByteArrayOutputStream()
            val connector =
                object : TerminalConnector by NoOpConnector {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        output.write(bytes, offset, length)
                    }
                }
            val provider =
                SwingShellSuggestionProvider { request ->
                    flowOf(
                        listOf(
                            if (change == "span") {
                                suggestion(request).copy(replacementStartOffset = 1)
                            } else {
                                suggestion(request)
                            },
                        ),
                    )
                }
            Fixture(
                sessionHandler = true,
                connector = connector,
                provider = provider,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ).use { fixture ->
                fixture.session.start(30, 4)
                if (change == "span") fixture.editableSource.value = TerminalShellCommandLineSnapshot("e\u0301", 2, 2, 0)
                onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
                fixture.view.awaitVisible()
                onEdt {
                    fixture.terminal.addShellSuggestionInvalidationListener {
                        when (change) {
                            "metadata" -> fixture.editableSource.value = snapshot().copy(cursorOffset = 1)
                            "aba" -> {
                                fixture.editableSource.value = null
                                fixture.editableSource.value = snapshot()
                            }
                            "close" -> fixture.session.close()
                        }
                    }
                    fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                }
                runCurrent()
                assertEquals(if (change == "fresh") "\u007f\u007f\u007f\u007f\u007freplacement" else "", output.toString(Charsets.UTF_8))
                val feedback = fixture.feedback.single()
                assertEquals(
                    if (change == "fresh") SwingShellSuggestionFeedbackKind.ACCEPTED else SwingShellSuggestionFeedbackKind.REJECTED,
                    feedback.kind,
                )
                if (change == "fresh") {
                    assertEquals(SwingShellSuggestionAcceptanceResult.ACCEPTED, feedback.acceptanceResult)
                } else {
                    assertTrue(feedback.acceptanceResult != SwingShellSuggestionAcceptanceResult.ACCEPTED)
                }
            }
        }

    @Test
    fun `default session handler rejects legacy observation only sources`() {
        Fixture(sessionHandler = true, versionedModel = false).use { fixture ->
            fixture.session.start(30, 4)
            onEdt {
                fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget)
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                assertTrue(fixture.requests.isEmpty())
            }
        }
    }

    @Test
    fun `default acceptance rejects input admitted by the final invalidation callback`() =
        runTest {
            val output = java.io.ByteArrayOutputStream()
            val connector =
                object : TerminalConnector by NoOpConnector {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        output.write(bytes, offset, length)
                    }
                }
            Fixture(sessionHandler = true, connector = connector, ioDispatcher = StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.session.start(30, 4)
                onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
                fixture.view.awaitVisible()
                onEdt {
                    fixture.terminal.addShellSuggestionInvalidationListener {
                        fixture.session.submitBytes("x".toByteArray())
                    }
                    fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                }
                runCurrent()
                assertEquals("x", output.toString(Charsets.UTF_8))
                assertEquals(SwingShellSuggestionFeedbackKind.REJECTED, fixture.feedback.single().kind)
                assertEquals(SwingShellSuggestionAcceptanceResult.STALE_CONTEXT, fixture.feedback.single().acceptanceResult)
            }
        }

    @Test
    fun `failure diagnostics reject off EDT and disposed configuration`() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalStateException> { fixture.terminal.setShellSuggestionFailureHandler(null) }
            onEdt {
                fixture.terminal.setShellSuggestionFailureHandler(null)
                fixture.terminal.dispose()
                assertFailsWith<IllegalStateException> { fixture.terminal.setShellSuggestionFailureHandler(null) }
            }
        }
    }

    @Test
    fun `obsolete session provider failure is excluded from diagnostics`() {
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val reports = mutableListOf<Exception>()
        try {
            Fixture(provider = { request ->
                flow {
                    emit(listOf(suggestion(request)))
                    try {
                        withContext(NonCancellable) {
                            release.await()
                            error("obsolete provider failure")
                        }
                    } finally {
                        finished.complete(Unit)
                    }
                }
            }).use { fixture ->
                onEdt {
                    fixture.terminal.setShellSuggestionFailureHandler { _, failure -> reports += failure }
                    fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget)
                }
                fixture.view.awaitVisible()
                fixture.awaitObservation(active = true)
                onEdt {
                    release.complete(Unit)
                    finished.awaitCompletion()
                    fixture.source.value = null
                }
                fixture.view.awaitHidden()
                fixture.awaitObservation(active = false)
                onEdt {
                    assertTrue(reports.isEmpty())
                    assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `provider failures report once on EDT after cleanup and permit reentrant requests`(constructionFailure: Boolean) {
        val failure = IllegalStateException("provider failure")
        val reported = CompletableDeferred<Unit>()
        val reports = mutableListOf<Exception>()
        Fixture(provider = { request ->
            if (request.commandText == "bad") {
                if (constructionFailure) throw failure
                flow { throw failure }
            } else {
                flowOf(listOf(suggestion(request)))
            }
        }).use { fixture ->
            onEdt {
                fixture.terminal.setShellSuggestionFailureHandler { request, error ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertEquals("bad", request.commandText)
                    assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                    reports += error
                    fixture.request(request("good"), SwingShellSuggestionTrigger.EXPLICIT)
                    reported.complete(Unit)
                }
                fixture.request(request("bad"), SwingShellSuggestionTrigger.EXPLICIT)
            }
            reported.awaitCompletion()
            fixture.view.awaitVisible()
            onEdt {
                assertEquals(1, reports.size)
                // Coroutine stack-trace recovery may copy an exception and retain its original as the cause.
                assertTrue(reports.single() === failure || reports.single().cause === failure)
                assertTrue(fixture.terminal.currentShellSuggestionState().visible)
            }
        }
    }

    @Test
    fun `provider cancellation is excluded from diagnostics and clears partial results`() {
        val finish = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        Fixture(provider = { request ->
            flow {
                try {
                    emit(listOf(suggestion(request)))
                    finish.await()
                    throw CancellationException("provider cancelled")
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }).use { fixture ->
            onEdt {
                fixture.terminal.setShellSuggestionFailureHandler { _, _ -> error("Cancellation was reported") }
                fixture.request(request(), SwingShellSuggestionTrigger.EXPLICIT)
            }
            fixture.view.awaitVisible()
            finish.complete(Unit)
            cancelled.awaitCompletion()
            fixture.view.awaitHidden()
            onEdt { assertFalse(fixture.terminal.currentShellSuggestionState().visible) }
        }
    }

    @Test
    fun `explicit supplied context uses provider and acceptance without a bound session or automatic popup`() {
        Fixture(bind = false).use { fixture ->
            val request = SwingShellSuggestionRequest("host --flag", 4)
            onEdt { fixture.request(request, SwingShellSuggestionTrigger.EXPLICIT) }
            assertEquals(request, fixture.awaitRequest())
            fixture.view.awaitVisible()
            onEdt {
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertEquals(request, fixture.accepted.single().request)
                assertEquals(request, fixture.feedback.single().request)
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
            }
            assertEquals(0, fixture.source.subscriptionCount.value)
        }
    }

    @ParameterizedTest
    @EnumSource(SwingShellSuggestionTrigger::class)
    fun `master toggle rejects every supplied and session request trigger`(trigger: SwingShellSuggestionTrigger) {
        Fixture(
            settings =
                SwingSettings.create { draft ->
                    draft.smartSuggestionsEnabled = false
                },
        ).use { fixture ->
            onEdt {
                fixture.request(request(), trigger)
                fixture.terminal.requestActiveShellSuggestions(trigger, editTarget = fixture.editTarget)
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
            }
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.source.subscriptionCount.value)
        }
    }

    @Test
    fun `automatic supplied and session requests remain disabled by automatic popup preference`() {
        Fixture().use { fixture ->
            onEdt {
                fixture.request(request(), SwingShellSuggestionTrigger.AUTOMATIC)
                fixture.terminal.requestActiveShellSuggestions(SwingShellSuggestionTrigger.AUTOMATIC, editTarget = fixture.editTarget)
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
            }
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.source.subscriptionCount.value)
        }
    }

    @Test
    fun `supplied context remains independent of a different bound session source`() {
        val release = CompletableDeferred<Unit>()
        Fixture(provider = { request ->
            flow {
                release.await()
                emit(listOf(suggestion(request)))
            }
        }).use { fixture ->
            val supplied = SwingShellSuggestionRequest("host --flag", 4)
            onEdt { fixture.request(supplied, SwingShellSuggestionTrigger.EXPLICIT) }
            assertEquals(supplied, fixture.awaitRequest())
            fixture.source.value = null
            release.complete(Unit)
            fixture.view.awaitVisible()
            onEdt {
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertEquals(supplied, fixture.accepted.single().request)
            }
            assertEquals(0, fixture.source.subscriptionCount.value)
        }
    }

    @ParameterizedTest
    @EnumSource(SwingShellSuggestionTrigger::class)
    fun `new explicit request cancels either kind of previous provider request`(previousTrigger: SwingShellSuggestionTrigger) {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        Fixture(
            settings =
                SwingSettings.create { draft ->
                    draft.smartSuggestionsEnabled = true
                    draft.shellSuggestionsEnabled = true
                },
            provider = { request ->
                flow {
                    if (request.commandText == "first") {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    emit(listOf(suggestion(request)))
                }
            },
        ).use { fixture ->
            onEdt { fixture.request(request("first"), previousTrigger) }
            assertEquals("first", fixture.awaitRequest().commandText)
            started.awaitCompletion()
            onEdt { fixture.request(request("second"), SwingShellSuggestionTrigger.EXPLICIT) }
            cancelled.awaitCompletion()
            assertEquals("second", fixture.awaitRequest().commandText)
            fixture.view.awaitVisible()
            onEdt {
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertEquals(
                    "second",
                    fixture.accepted
                        .single()
                        .request.commandText,
                )
            }
        }
    }

    @Test
    fun `disabling automatic popup preserves an explicit session request`() {
        val release = CompletableDeferred<Unit>()
        Fixture(
            settings =
                SwingSettings.create { draft ->
                    draft.smartSuggestionsEnabled = true
                    draft.shellSuggestionsEnabled = true
                },
            provider = { request ->
                flow {
                    release.await()
                    emit(listOf(suggestion(request)))
                }
            },
        ).use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.awaitRequest()
            onEdt {
                fixture.settings =
                    fixture.settings.copy { draft ->
                        draft.shellSuggestionsEnabled = false
                    }
                fixture.terminal.reloadSettings()
            }
            release.complete(Unit)
            fixture.view.awaitVisible()
            onEdt { assertTrue(fixture.terminal.currentShellSuggestionState().visible) }
        }
    }

    @Test
    fun `disabled automatic calls do not cancel a pending explicit request`() {
        val release = CompletableDeferred<Unit>()
        Fixture(provider = { request ->
            flow {
                release.await()
                emit(listOf(suggestion(request)))
            }
        }).use { fixture ->
            onEdt { fixture.request(request("explicit"), SwingShellSuggestionTrigger.EXPLICIT) }
            assertEquals("explicit", fixture.awaitRequest().commandText)
            onEdt {
                fixture.source.value = null
                fixture.request(request("automatic"), SwingShellSuggestionTrigger.AUTOMATIC)
                fixture.terminal.requestActiveShellSuggestions(SwingShellSuggestionTrigger.AUTOMATIC, editTarget = fixture.editTarget)
            }
            release.complete(Unit)
            fixture.view.awaitVisible()
            onEdt {
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertEquals(
                    "explicit",
                    fixture.accepted
                        .single()
                        .request.commandText,
                )
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `disabled automatic calls leave an explicit popup visible`() {
        Fixture().use { fixture ->
            onEdt { fixture.request(request("explicit"), SwingShellSuggestionTrigger.EXPLICIT) }
            assertEquals("explicit", fixture.awaitRequest().commandText)
            fixture.view.awaitVisible()
            onEdt {
                fixture.source.value = null
                fixture.request(request("automatic"), SwingShellSuggestionTrigger.AUTOMATIC)
                fixture.terminal.requestActiveShellSuggestions(SwingShellSuggestionTrigger.AUTOMATIC, editTarget = fixture.editTarget)
                assertTrue(fixture.terminal.currentShellSuggestionState().visible)
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @EnumSource(ContextChange::class)
    fun `session context changes cancel a pending request and release observation`(change: ContextChange) {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        Fixture(provider = {
            flow {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }).use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.awaitRequest()
            started.awaitCompletion()
            fixture.awaitObservation(active = true)
            fixture.source.value = change.apply(snapshot())
            cancelled.awaitCompletion()
            fixture.awaitObservation(active = false)
            onEdt { assertFalse(fixture.terminal.currentShellSuggestionState().visible) }
        }
    }

    @ParameterizedTest
    @EnumSource(SwingShellSuggestionTrigger::class)
    fun `session requests keep context observation until a completed provider popup is hidden`(trigger: SwingShellSuggestionTrigger) {
        val finished = CompletableDeferred<Unit>()
        Fixture(
            settings =
                SwingSettings.create { draft ->
                    draft.smartSuggestionsEnabled = true
                    draft.shellSuggestionsEnabled = true
                },
            provider = { request ->
                flow {
                    emit(listOf(suggestion(request)))
                    finished.complete(Unit)
                }
            },
        ).use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(trigger, editTarget = fixture.editTarget) }
            fixture.view.awaitVisible()
            finished.awaitCompletion()
            fixture.awaitObservation(active = true)
            fixture.source.value = null
            fixture.view.awaitHidden()
            // Configured automatic completion retains its own session observation until disabled.
            onEdt { fixture.terminal.setShellSuggestionProvider(null) }
            fixture.awaitObservation(active = false)
        }
    }

    @Test
    fun `finite empty provider releases session context observation`() {
        val release = CompletableDeferred<Unit>()
        Fixture(provider = {
            flow {
                release.await()
                emit(emptyList())
            }
        }).use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.awaitRequest()
            fixture.awaitObservation(active = true)
            release.complete(Unit)
            fixture.awaitObservation(active = false)
            onEdt { assertFalse(fixture.terminal.currentShellSuggestionState().visible) }
        }
    }

    @Test
    fun `provider failure hides its published popup releases observation and permits a new request`() {
        val failProvider = CompletableDeferred<Unit>()
        Fixture(provider = { request ->
            flow {
                emit(listOf(suggestion(request)))
                if (request.commandText == "git s") {
                    failProvider.await()
                    error("Expected suggestion provider failure")
                }
            }
        }).use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            assertEquals("git s", fixture.awaitRequest().commandText)
            fixture.view.awaitVisible()
            fixture.awaitObservation(active = true)

            failProvider.complete(Unit)
            fixture.view.awaitHidden()
            fixture.awaitObservation(active = false)

            fixture.source.value = snapshot().copy(commandText = "git log", cursorOffset = 7, cursorColumn = 11)
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            assertEquals("git log", fixture.awaitRequest().commandText)
            fixture.view.awaitVisible()
            onEdt {
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertEquals(
                    "git log",
                    fixture.accepted
                        .single()
                        .request.commandText,
                )
            }
            fixture.awaitObservation(active = false)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `escape from a passive popup releases observation and prevents further provider publication`(progressive: Boolean) {
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        try {
            Fixture(provider = { request ->
                flow {
                    try {
                        emit(listOf(suggestion(request)))
                        if (progressive) {
                            withContext(NonCancellable) { release.await() }
                            emit(listOf(suggestion(request).copy(replacementText = "late")))
                        }
                    } finally {
                        finished.complete(Unit)
                    }
                }
            }).use { fixture ->
                onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
                fixture.view.awaitVisible()
                fixture.awaitObservation(active = true)
                onEdt {
                    assertEquals(-1, fixture.terminal.currentShellSuggestionState().selectedIndex)
                    assertTrue(fixture.press(KeyEvent.VK_ESCAPE).isConsumed)
                    assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                    assertTrue(fixture.feedback.isEmpty())
                }
                fixture.view.awaitHidden()
                fixture.awaitObservation(active = false)
                release.complete(Unit)
                finished.awaitCompletion()
                onEdt { assertFalse(fixture.terminal.currentShellSuggestionState().visible) }
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `provider result checks authoritative context before the revision tracker starts`() {
        val worker = StandardTestDispatcher()
        val dispatches = LinkedBlockingQueue<Runnable>()
        val release = CompletableDeferred<Unit>()
        try {
            Fixture(
                workerDispatcher = worker,
                uiDispatcher = TerminalUiDispatcher { dispatches += it },
                provider = { request ->
                    flow {
                        release.await()
                        emit(listOf(suggestion(request)))
                    }
                },
            ).use { fixture ->
                onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
                fixture.awaitRequest()
                onEdt { while (true) (dispatches.poll() ?: break).run() }
                assertEquals(0, fixture.source.subscriptionCount.value)

                fixture.source.value = null
                release.complete(Unit)
                val publication = dispatches.poll(5, TimeUnit.SECONDS) ?: error("Provider did not dispatch its result")
                onEdt {
                    publication.run()
                    while (true) (dispatches.poll() ?: break).run()
                    assertFalse(fixture.terminal.currentShellSuggestionState().visible)
                    assertTrue(fixture.accepted.isEmpty())
                }
            }
        } finally {
            release.complete(Unit)
            onEdt { while (true) (dispatches.poll() ?: break).run() }
            worker.scheduler.runCurrent()
        }
    }

    @Test
    fun `requesting unavailable active context cancels a pending supplied-context request`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        Fixture(provider = {
            flow {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }).use { fixture ->
            fixture.source.value = null
            onEdt { fixture.request(request(), SwingShellSuggestionTrigger.EXPLICIT) }
            fixture.awaitRequest()
            started.awaitCompletion()
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            cancelled.awaitCompletion()
            onEdt { assertFalse(fixture.terminal.currentShellSuggestionState().visible) }
        }
    }

    @ParameterizedTest
    @EnumSource(RequestEnd::class)
    fun `session request cancellation follows component and session lifetime`(end: RequestEnd) {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        Fixture(provider = {
            flow {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }).use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.awaitRequest()
            started.awaitCompletion()
            fixture.awaitObservation(active = true)
            onEdt { fixture.endRequest(end) }
            cancelled.awaitCompletion()
            fixture.awaitObservation(active = false)
            onEdt { assertFalse(fixture.terminal.currentShellSuggestionState().visible) }
        }
    }

    @ParameterizedTest
    @EnumSource(ContextChange::class)
    fun `acceptance checks current authoritative context before queued revision callbacks`(change: ContextChange) {
        Fixture().use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.view.awaitVisible()
            onEdt {
                fixture.source.value = change.apply(snapshot())
                fixture.view.listener.onSuggestionClicked(fixture.view.snapshot, 0)
                assertTrue(fixture.accepted.isEmpty())
                assertTrue(fixture.feedback.isEmpty())
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
            }
        }
    }

    @Test
    fun `stale dismissal does not send feedback for obsolete context`() {
        Fixture().use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.view.awaitVisible()
            onEdt {
                fixture.press(KeyEvent.VK_DOWN)
                fixture.source.value = null
                fixture.press(KeyEvent.VK_ESCAPE)
                assertTrue(fixture.feedback.isEmpty())
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
            }
        }
    }

    @Test
    fun `session closure hides a popup after its provider has completed`() {
        Fixture().use { fixture ->
            onEdt { fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget) }
            fixture.view.awaitVisible()
            fixture.awaitObservation(active = true)
            fixture.session.close()
            fixture.view.awaitHidden()
            fixture.awaitObservation(active = false)
        }
    }

    @Test
    fun `disposed component does not restart supplied or session suggestions`() {
        Fixture().use { fixture ->
            onEdt {
                fixture.terminal.dispose()
                fixture.request(request(), SwingShellSuggestionTrigger.EXPLICIT)
                fixture.terminal.requestActiveShellSuggestions(editTarget = fixture.editTarget)
                fixture.terminal.showSuggestions(request(), listOf(suggestion(request())))
                assertFalse(fixture.terminal.currentShellSuggestionState().visible)
            }
            assertTrue(fixture.requests.isEmpty())
            assertEquals(0, fixture.source.subscriptionCount.value)
        }
    }

    enum class ContextChange {
        TEXT,
        CURSOR,
        ANCHOR,
        UNAVAILABLE,
        ;

        fun apply(snapshot: TerminalShellCommandLineSnapshot): TerminalShellCommandLineSnapshot? =
            when (this) {
                TEXT -> snapshot.copy(commandText = "git status")
                CURSOR -> snapshot.copy(cursorOffset = 2)
                ANCHOR -> snapshot.copy(cursorRow = 1)
                UNAVAILABLE -> null
            }
    }

    enum class RequestEnd {
        HIDE,
        INPUT,
        FOCUS_LOSS,
        UNBIND,
        REBIND,
        DISPOSE,
        SESSION_CLOSE,
        MASTER_OFF,
    }

    private class Fixture(
        var settings: SwingSettings =
            SwingSettings.create { draft ->
                draft.smartSuggestionsEnabled = true
                draft.shellSuggestionsEnabled = false
            },
        bind: Boolean = true,
        provider: SwingShellSuggestionProvider = SwingShellSuggestionProvider { flowOf(listOf(suggestion(it))) },
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
        suggestionDispatcher: CoroutineDispatcher? = null,
        uiDispatcher: TerminalUiDispatcher = TerminalUiDispatcher.SWING,
        sessionHandler: Boolean = false,
        versionedModel: Boolean = sessionHandler,
        connector: TerminalConnector = NoOpConnector,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : AutoCloseable {
        var feedHost: (String) -> Unit = { error("Host input is not installed in this fixture") }
        val mutationDispatcher = StandardTestDispatcher()
        val source = MutableStateFlow<TerminalShellCommandLineSnapshot?>(snapshot())
        val editableSource = TerminalShellCommandLineState(snapshot())
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 30, height = 4),
                connector = connector,
                shellIntegration =
                    if (versionedModel) {
                        TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), editableSource)
                    } else {
                        TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), source)
                    },
                workerDispatcher = workerDispatcher,
                ioDispatcher = ioDispatcher,
            )
        val replacementSession =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 30, height = 4),
                connector = NoOpConnector,
            )
        val requests = LinkedBlockingQueue<SwingShellSuggestionRequest>()
        val accepted = ArrayList<SwingShellSuggestionAcceptance>()
        val feedback = ArrayList<SwingShellSuggestionFeedback>()
        val view = RecordingView()
        val editTarget: SwingShellSuggestionEditTarget? =
            if (sessionHandler) {
                null
            } else {
                SwingShellSuggestionEditTarget { _ ->
                    SwingShellSuggestionHandler {
                        accepted += it
                        SwingShellSuggestionAcceptanceResult.ACCEPTED
                    }
                }
            }
        private val suggestionProvider: SwingShellSuggestionProvider =
            object : SwingShellSuggestionProvider {
                override fun suggestions(request: SwingShellSuggestionRequest): Flow<List<SwingShellSuggestion>> =
                    flow {
                        requests += request
                        emitAll(provider.suggestions(request))
                    }

                override fun open(request: SwingShellSuggestionRequest): SwingShellSuggestionSource =
                    SwingShellSuggestionSource(
                        suggestions = suggestions(request),
                        feedbackHandler = { feedback += it },
                    )
            }
        val terminal =
            onEdt {
                SwingTerminal(
                    settingsProvider = { settings },
                    hostServices =
                        SwingHostServices.create { draft ->
                            draft.uiDispatcher = uiDispatcher
                            draft.shellSuggestionViewFactory = { listener -> view.apply { this.listener = listener } }
                        },
                    searchDispatcher = Dispatchers.Default,
                    suggestionDispatcher = suggestionDispatcher,
                    mutationDispatcher = mutationDispatcher,
                ).also { terminal ->
                    terminal.size = terminal.preferredGridSize(30, 4)
                    terminal.setShellSuggestionProvider(suggestionProvider)
                    if (bind) {
                        terminal.bind(session)
                        mutationDispatcher.scheduler.runCurrent()
                    }
                }
            }

        fun request(
            request: SwingShellSuggestionRequest,
            trigger: SwingShellSuggestionTrigger,
        ) {
            terminal.requestShellSuggestions(request.commandText, request.cursorOffset, 9, 0, trigger, editTarget)
        }

        fun awaitRequest(): SwingShellSuggestionRequest = requests.poll(5, TimeUnit.SECONDS) ?: error("Provider was not requested")

        fun awaitObservation(active: Boolean) =
            runBlocking {
                withTimeout(5_000.milliseconds) { source.subscriptionCount.first { (it > 0) == active } }
            }

        fun press(
            keyCode: Int,
            modifiers: Int = 0,
        ): KeyEvent {
            val event = KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
            terminal.keyListeners.forEach { it.keyPressed(event) }
            return event
        }

        fun endRequest(end: RequestEnd) {
            when (end) {
                RequestEnd.HIDE -> terminal.hideShellSuggestions()
                RequestEnd.INPUT -> press(KeyEvent.VK_BACK_SPACE)
                RequestEnd.FOCUS_LOSS -> terminal.focusListeners.forEach { it.focusLost(FocusEvent(terminal, FocusEvent.FOCUS_LOST)) }
                RequestEnd.UNBIND -> terminal.unbind()
                RequestEnd.REBIND -> terminal.bind(replacementSession)
                RequestEnd.DISPOSE -> terminal.dispose()
                RequestEnd.SESSION_CLOSE -> session.close()
                RequestEnd.MASTER_OFF -> {
                    settings =
                        settings.copy { draft ->
                            draft.smartSuggestionsEnabled = false
                        }
                    terminal.reloadSettings()
                }
            }
        }

        override fun close() {
            onEdt { terminal.dispose() }
            session.close()
            replacementSession.close()
        }
    }

    private class RecordingView : SwingShellSuggestionView {
        val visibilityChanges = ArrayList<Boolean>()
        override val component =
            object : JPanel() {
                override fun setVisible(visible: Boolean) {
                    if (isVisible != visible) visibilityChanges += visible
                    super.setVisible(visible)
                }
            }
        lateinit var listener: SwingShellSuggestionViewListener
        var snapshot = SwingShellSuggestionViewSnapshot.EMPTY
            private set
        private val updates = LinkedBlockingQueue<SwingShellSuggestionViewSnapshot>()

        override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            this.snapshot = snapshot
            updates += snapshot
        }

        fun awaitVisible() {
            while (true) {
                val update = updates.poll(5, TimeUnit.SECONDS) ?: error("Visible suggestion update was not published")
                if (update.visibleSuggestions.isNotEmpty()) return
            }
        }

        fun awaitHidden() {
            val update = updates.poll(5, TimeUnit.SECONDS) ?: error("Hidden suggestion update was not published")
            assertTrue(update.visibleSuggestions.isEmpty())
        }
    }

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }

    private companion object {
        fun snapshot() = TerminalShellCommandLineSnapshot("git s", 5, 9, 0)

        fun request(text: String = "git s") = SwingShellSuggestionRequest(text, text.length)

        fun suggestion(request: SwingShellSuggestionRequest) =
            SwingShellSuggestion(
                replacementText = "replacement",
                replacementStartOffset = 0,
                replacementEndOffset = request.commandText.length,
                source = "test",
                kind = "COMMAND",
            )

        fun <T> onEdt(action: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return action()
            var result: Result<T>? = null
            SwingUtilities.invokeAndWait { result = runCatching(action) }
            return checkNotNull(result).getOrThrow()
        }

        fun CompletableDeferred<Unit>.awaitCompletion() = runBlocking { withTimeout(5_000.milliseconds) { await() } }
    }
}
