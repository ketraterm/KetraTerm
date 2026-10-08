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
import java.awt.event.KeyEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalSuggestionContextTest {
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
                onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
                fixture.terminal.requestActiveShellSuggestions()
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
                onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
                    fixture.terminal.requestActiveShellSuggestions()
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
                fixture.terminal.requestActiveShellSuggestions(trigger)
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
                fixture.terminal.requestActiveShellSuggestions(SwingShellSuggestionTrigger.AUTOMATIC)
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
                fixture.terminal.requestActiveShellSuggestions(SwingShellSuggestionTrigger.AUTOMATIC)
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
                fixture.terminal.requestActiveShellSuggestions(SwingShellSuggestionTrigger.AUTOMATIC)
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions(trigger) }
            fixture.view.awaitVisible()
            finished.awaitCompletion()
            fixture.awaitObservation(active = true)
            fixture.source.value = null
            fixture.view.awaitHidden()
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
            assertEquals("git s", fixture.awaitRequest().commandText)
            fixture.view.awaitVisible()
            fixture.awaitObservation(active = true)

            failProvider.complete(Unit)
            fixture.view.awaitHidden()
            fixture.awaitObservation(active = false)

            fixture.source.value = snapshot().copy(commandText = "git log", cursorOffset = 7, cursorColumn = 11)
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
                onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
                onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
            onEdt { fixture.terminal.requestActiveShellSuggestions() }
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
                fixture.terminal.requestActiveShellSuggestions()
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
        uiDispatcher: TerminalUiDispatcher = TerminalUiDispatcher.SWING,
        sessionHandler: Boolean = false,
        versionedModel: Boolean = sessionHandler,
        connector: TerminalConnector = NoOpConnector,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : AutoCloseable {
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
        val terminal =
            onEdt {
                SwingTerminal(
                    settingsProvider = { settings },
                    hostServices =
                        SwingHostServices.create { draft ->
                            draft.uiDispatcher = uiDispatcher
                            draft.shellSuggestionProvider =
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
                            draft.shellSuggestionEditTarget =
                                if (sessionHandler) {
                                    SwingShellSuggestionEditTarget.createDefault(session)
                                } else {
                                    SwingShellSuggestionEditTarget { _ ->
                                        SwingShellSuggestionHandler {
                                            accepted += it
                                            SwingShellSuggestionAcceptanceResult.ACCEPTED
                                        }
                                    }
                                }
                            draft.shellSuggestionViewFactory = { listener -> view.apply { this.listener = listener } }
                        },
                ).also { terminal ->
                    terminal.size = terminal.preferredGridSize(30, 4)
                    if (bind) terminal.bind(session)
                }
            }

        fun request(
            request: SwingShellSuggestionRequest,
            trigger: SwingShellSuggestionTrigger,
        ) {
            terminal.requestShellSuggestions(request.commandText, request.cursorOffset, 9, 0, trigger)
        }

        fun awaitRequest(): SwingShellSuggestionRequest = requests.poll(5, TimeUnit.SECONDS) ?: error("Provider was not requested")

        fun awaitObservation(active: Boolean) =
            runBlocking {
                withTimeout(5_000.milliseconds) { source.subscriptionCount.first { (it > 0) == active } }
            }

        fun press(keyCode: Int): KeyEvent {
            val event = KeyEvent(terminal, KeyEvent.KEY_PRESSED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED)
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
        override val component = JPanel()
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
