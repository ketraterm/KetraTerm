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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.Runnable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalHyperlinkDiscoverySchedulingTest {
    @ParameterizedTest
    @ValueSource(strings = ["exception", "assertion", "cancellation"])
    fun `reset releases configuration and actions after provider cleanup fails`(kind: String) {
        val failure =
            when (kind) {
                "assertion" -> AssertionError("Provider cleanup failed")
                "cancellation" -> CancellationException("Provider cleanup cancelled")
                else -> IllegalStateException("Provider cleanup failed")
            }
        var failCleanup = false
        var subscriptions = 0
        var cancellations = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationChanges =
                    kotlinx.coroutines.flow.flow<Unit> {
                        subscriptions++
                        try {
                            awaitCancellation()
                        } finally {
                            cancellations++
                        }
                    }

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    listOf(request.hyperlink(0, 0, URL.length, { true }))

                override fun discardOrderedState() {
                    if (failCleanup) throw failure
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.completeAnalysis()
            assertEquals(1, subscriptions)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            failCleanup = true
            try {
                fixture.onEdt { assertSame(failure, assertThrows(Throwable::class.java) { fixture.controller.reset() }) }
            } finally {
                failCleanup = false
            }
            fixture.completeAnalysis()
            assertEquals(1, cancellations)
            fixture.onEdt { assertFalse(fixture.openAt(0)) }
            fixture.show(URL)
            fixture.completeAnalysis()
            assertEquals(2, subscriptions)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["exception", "assertion", "cancellation"])
    fun `failed ordered cleanup retires an executing lane before a replacement binding starts`(kind: String) {
        val failure =
            when (kind) {
                "assertion" -> AssertionError("Provider cleanup failed")
                "cancellation" -> CancellationException("Provider cleanup cancelled")
                else -> IllegalStateException("Provider cleanup failed")
            }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var discards = 0
        var failCleanup = false
        val detector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.ORDERED_CONTENT

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
                    if (++calls == 1) withContext(NonCancellable) { gate.await() }
                    return listOf(request.hyperlink(0, 0, URL.length, { true }))
                }

                override fun discardOrderedState() {
                    discards++
                    if (failCleanup) {
                        failCleanup = false
                        throw failure
                    }
                }
            }
        Fixture(detector = detector).use { fixture ->
            try {
                fixture.show(URL)
                fixture.completeAnalysis()
                assertEquals(1, calls)
                failCleanup = true
                fixture.onEdt { fixture.controller.reset() }
                fixture.show("$URL/next")
                gate.complete(Unit)
                repeat(3) { fixture.completeAnalysis() }
                assertEquals(1, discards)
                assertEquals(2, calls)
                if (failure is CancellationException) {
                    assertNull(fixture.failures.poll())
                } else {
                    assertSame(failure, fixture.failures.poll())
                    assertTrue(failure.suppressed.single() is CancellationException)
                }
                fixture.onEdt { assertTrue(fixture.openAt(0)) }
            } finally {
                failCleanup = false
                gate.complete(Unit)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed cleanup after cancellation before dispatch releases the ordered lane`(error: Boolean) {
        val failure = if (error) AssertionError("Provider cleanup failed") else IllegalStateException("Provider cleanup failed")
        var failCleanup = false
        val detector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.ORDERED_CONTENT

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    listOf(request.hyperlink(0, 0, URL.length, { true }))

                override fun discardOrderedState() {
                    if (failCleanup) throw failure
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.completeAnalysis()
            fixture.onEdt { fixture.controller.reset() }
            fixture.drainUi()
            fixture.show(URL)
            failCleanup = true
            var synchronousFailure: Throwable? = null
            try {
                fixture.onEdt {
                    try {
                        fixture.controller.reset()
                    } catch (next: Throwable) {
                        synchronousFailure = next
                    }
                }
                fixture.drainUi()
            } finally {
                failCleanup = false
            }
            val failures = fixture.failures.toList() + listOfNotNull(synchronousFailure)
            assertTrue(failures.any { it === failure || it.cause === failure }, "The provider failure must remain observable")
            assertTrue(failure.suppressed.single() is CancellationException)
            fixture.failures.clear()
            fixture.show(URL)
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
        }
    }

    @Test
    fun `provider cleanup failure does not end configuration collection`() {
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var generation = 0L
        var calls = 0
        var subscriptions = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationGeneration get() = generation
                override val configurationChanges =
                    kotlinx.coroutines.flow.flow {
                        subscriptions++
                        changes.collect { emit(it) }
                    }

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
                    calls++
                    return listOf(request.hyperlink(0, 0, URL.length, { true }))
                }

                override fun discardOrderedState() {
                    if (generation == 1L) throw IllegalStateException("Provider cleanup failed")
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.completeAnalysis()
            repeat(2) {
                generation++
                assertTrue(changes.tryEmit(Unit))
                fixture.drainUi()
                fixture.completeAnalysis()
            }
            assertEquals(3, calls)
            assertEquals(1, subscriptions)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `provider cancellation and errors propagate after retiring its configuration`(cancelled: Boolean) {
        val failure = if (cancelled) CancellationException("Provider cancelled") else AssertionError("Provider failed")
        var failCleanup = false
        var generation = 0L
        var subscriptions = 0
        var cancellations = 0
        var calls = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationGeneration get() = generation
                override val configurationChanges =
                    kotlinx.coroutines.flow.flow<Unit> {
                        subscriptions++
                        try {
                            awaitCancellation()
                        } finally {
                            cancellations++
                        }
                    }

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> {
                    calls++
                    return listOf(request.hyperlink(0, 0, URL.length, { true }))
                }

                override fun discardOrderedState() {
                    if (failCleanup) throw failure
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.completeAnalysis()
            failCleanup = true
            try {
                fixture.onEdt {
                    generation++
                    assertSame(failure, assertThrows(Throwable::class.java) { fixture.controller.scheduleForFrame() })
                }
            } finally {
                failCleanup = false
            }
            fixture.drainUi()
            assertEquals(1, cancellations)
            fixture.onEdt { fixture.controller.reconcile() }
            fixture.completeAnalysis()
            assertEquals(2, calls)
            assertEquals(2, subscriptions)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
        }
    }

    @Test
    fun `idle provider replacement retires its subscription after cleanup fails`() {
        var failCleanup = false
        var cancelled = false
        var replacementCalls = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationChanges =
                    kotlinx.coroutines.flow.flow<Unit> {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    listOf(request.hyperlink(0, 0, URL.length, { true }))

                override fun discardOrderedState() {
                    if (failCleanup) throw IllegalStateException("Provider cleanup failed")
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.completeAnalysis()
            failCleanup = true
            fixture.replaceDetector { request ->
                replacementCalls++
                listOf(request.hyperlink(0, 0, URL.length, { true }))
            }
            fixture.completeAnalysis()
            assertTrue(cancelled)
            assertEquals(1, replacementCalls)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
        }
    }

    @Test
    fun `provider cleanup cannot overwrite a reentrant replacement binding`() {
        var onDiscard: () -> Unit = {}
        val detector =
            object : SwingHyperlinkDetector {
                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> = emptyList()

                override fun discardOrderedState() {
                    val action = onDiscard
                    onDiscard = {}
                    action()
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.completeAnalysis()
            var intermediateCalls = 0
            var replacementCalls = 0
            onDiscard = {
                fixture.controller.reset()
                fixture.replaceDetector { request ->
                    replacementCalls++
                    listOf(request.hyperlink(0, 0, URL.length, { true }))
                }
            }
            fixture.replaceDetector {
                intermediateCalls++
                emptyList()
            }
            fixture.completeAnalysis()
            assertEquals(0, intermediateCalls)
            assertEquals(1, replacementCalls)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["scan", "validation"])
    fun `blocked source work leaves EDT responsive and reset discards its result`(phase: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture().use { fixture ->
            Executors.newSingleThreadExecutor().use { executor ->
                try {
                    fixture.show(URL)
                    fixture.drainUi()
                    if (phase == "scan") {
                        fixture.completeAnalysis()
                        fixture.onEdt { fixture.controller.reset() }
                        fixture.worker.runCurrent()
                        fixture.drainUi()
                        fixture.show(URL)
                        fixture.drainUi()
                    } else {
                        fixture.worker.runCurrent() // Complete detection; validation is the next worker phase.
                        fixture.drainUi()
                    }
                    fixture.beforeSourceRead = {
                        assertFalse(SwingUtilities.isEventDispatchThread(), "Live source reads must stay on the worker")
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    val work = executor.submit { fixture.worker.runCurrent() }
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    val heartbeat =
                        FutureTask {
                            fixture.controller.reset()
                            assertFalse(fixture.openAt(0))
                        }
                    SwingUtilities.invokeLater(heartbeat)
                    heartbeat.get(10, TimeUnit.SECONDS)
                    release.countDown()
                    work.get(10, TimeUnit.SECONDS)
                    fixture.drainUi()
                    fixture.onEdt { assertFalse(fixture.openAt(0), "Obsolete source work cannot restore an action") }
                    fixture.beforeSourceRead = { assertFalse(SwingUtilities.isEventDispatchThread()) }
                    fixture.show(URL)
                    fixture.completeAnalysis()
                    fixture.onEdt { assertTrue(fixture.openAt(0)) }
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `absent source is inactive and unbinding cancels detection and retires actions`() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        val detector =
            SwingHyperlinkDetector { request ->
                if (++calls > 1) {
                    try {
                        gate.await()
                    } finally {
                        cancelled = true
                    }
                }
                listOf(request.hyperlink(0, 0, URL.length, { true }))
            }
        Fixture(detector = detector, sourceBound = false).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(0, calls)
            assertEquals(0, fixture.worker.pendingTasks)
            fixture.bindSource(true)
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            fixture.show("$URL/next")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(2, calls)
            fixture.onEdt { fixture.cache.reset() }
            fixture.bindSource(false)
            fixture.completeAnalysis()
            assertTrue(cancelled)
            fixture.onEdt { assertFalse(fixture.openAt(0)) }
            assertEquals(0, fixture.worker.pendingTasks)
        }
    }

    @ParameterizedTest
    @EnumSource(SwingHyperlinkDetectionContext::class)
    fun `configuration refresh preserves prepared links until replacement and updates their actions`(
        context: SwingHyperlinkDetectionContext,
    ) {
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val gate = CompletableDeferred<Unit>()
        val opened = ArrayList<Long>()
        var revision = 0L
        var subscriptions = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val context = context
                override val configurationGeneration get() = revision
                override val configurationChanges =
                    kotlinx.coroutines.flow.flow {
                        subscriptions++
                        changes.collect { emit(it) }
                    }

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    buildList {
                        if (context == SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED &&
                            request.context == SwingHyperlinkDetectionContext.ORDERED_CONTENT
                        ) {
                            return@buildList
                        }
                        val version = revision
                        if (version == 1L) gate.await()
                        if (version < 2L) {
                            add(
                                request.hyperlink(0, 0, URL.length, {
                                    opened.add(version)
                                    true
                                }, uri = URL),
                            )
                        }
                    }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            var original = 0
            fixture.onEdt {
                original = fixture.controller.hyperlinkIdAt(0, 0, fixture.cache)
                assertTrue(fixture.openAt(0))
            }
            revision++
            assertTrue(changes.tryEmit(Unit))
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt {
                assertEquals(original, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertTrue(fixture.openAt(0), "Refreshing providers must not blank a prepared link")
            }
            gate.complete(Unit)
            fixture.completeAnalysis()
            fixture.onEdt {
                assertNotEquals(original, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.controller.isDiscoveredHyperlinkResolvable(original, fixture.cache))
                assertTrue(fixture.openAt(0))
            }
            assertEquals(listOf(0L, 0L, 1L), opened)
            revision++
            assertTrue(changes.tryEmit(Unit))
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertFalse(fixture.openAt(0), "A successful empty refresh removes the old action") }
            assertEquals(1, subscriptions, "Configuration refresh must keep the binding's existing subscription")
        }
    }

    @Test
    fun `binding disposal releases ordered provider state only after its invocation exits`() {
        val gate = CompletableDeferred<Unit>()
        var running = false
        var discarded = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.ORDERED_CONTENT

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    buildList {
                        running = true
                        try {
                            withContext(NonCancellable) { gate.await() }
                        } finally {
                            running = false
                        }
                    }

                override fun discardOrderedState() {
                    assertFalse(running, "A binding must not clear provider state under a running filter")
                    discarded++
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            assertTrue(running)
            fixture.onEdt { fixture.controller.reset() }
            assertEquals(0, discarded)
            gate.complete(Unit)
            fixture.completeAnalysis()
            assertFalse(running)
            assertEquals(1, discarded)
        }
    }

    @Test
    fun `slow ordered filters cannot delay independent URLs`() {
        val gate = CompletableDeferred<Unit>()
        var orderedEntered = false
        val detector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    buildList {
                        if (request.context == SwingHyperlinkDetectionContext.ORDERED_CONTENT) {
                            orderedEntered = true
                            gate.await()
                            add(request.hyperlink(1, 0, 4, { true }))
                        } else {
                            addAll(findUrls(request, ArrayList(), true))
                        }
                    }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL, "file")
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.completeAnalysis()
            assertTrue(orderedEntered)
            fixture.onEdt {
                assertTrue(fixture.openAt(0))
                assertFalse(fixture.openAt(1))
            }
            gate.complete(Unit)
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
        }
    }

    @Test
    fun `provider configuration flow reconciles without a terminal frame`() {
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var revision = 0L
        var calls = 0
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationGeneration get() = revision
                override val configurationChanges = changes

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    buildList {
                        calls++
                        if (revision > 0) add(request.hyperlink(0, 0, URL.length, { true }))
                    }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(1, calls)
            fixture.onEdt { assertFalse(fixture.openAt(0)) }
            revision++
            assertTrue(changes.tryEmit(Unit))
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(2, calls)
            var prepared = 0
            fixture.onEdt { prepared = fixture.controller.hyperlinkIdAt(0, 0, fixture.cache) }
            assertTrue(changes.tryEmit(Unit))
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(2, calls, "An unchanged configuration generation must not trigger rediscovery")
            fixture.onEdt {
                assertEquals(prepared, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertTrue(fixture.openAt(0))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failure and provider cancellation recover without output or scrolling`(cancelled: Boolean) {
        var calls = 0
        Fixture(
            detector =
                SwingHyperlinkDetector { request ->
                    buildList {
                        if (++calls == 1) {
                            if (cancelled) throw CancellationException("Read interrupted") else error("Provider unavailable")
                        }
                        add(request.hyperlink(0, 0, URL.length, { true }))
                    }
                },
        ).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(1, calls)
            assertEquals(0, fixture.worker.pendingTasks)
            fixture.onEdt { fixture.uiDispatcher.scheduler.advanceTimeBy(100) }
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(2, calls)
        }
    }

    @Test
    fun `persistent failure exhausts bounded recovery and viewport frames cannot restart it`() {
        var calls = 0
        Fixture(
            detector =
                SwingHyperlinkDetector { _ ->
                    buildList {
                        calls++
                        error("Unavailable")
                    }
                },
        ).use { fixture ->
            fixture.show(URL, contentGeneration = 42L)
            fixture.drainUi()
            fixture.completeAnalysis()
            for (delay in longArrayOf(100, 500, 2000)) {
                fixture.onEdt { fixture.uiDispatcher.scheduler.advanceTimeBy(delay) }
                fixture.drainUi()
                fixture.completeAnalysis()
            }
            assertEquals(4, calls)
            repeat(10) {
                fixture.show(URL, contentGeneration = 42L)
                fixture.drainUi()
                fixture.completeAnalysis()
            }
            fixture.onEdt { fixture.uiDispatcher.scheduler.advanceTimeBy(60_000) }
            fixture.drainUi()
            assertEquals(4, calls)
            assertEquals(0, fixture.worker.pendingTasks)
            fixture.onEdt { fixture.controller.reconcile() }
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(5, calls, "A lifecycle return explicitly permits a fresh recovery attempt")
        }
    }

    @Test
    fun `successful empty analysis does not schedule recovery`() {
        var calls = 0
        Fixture(detector = SwingHyperlinkDetector { _ -> buildList { calls++ } }).use { fixture ->
            fixture.show("plain")
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.finishSourceScan()
            fixture.onEdt { fixture.uiDispatcher.scheduler.advanceTimeBy(60_000) }
            fixture.drainUi()
            assertEquals(1, calls)
            assertEquals(0, fixture.worker.pendingTasks)
        }
    }

    @Test
    fun `independent results publish in bounded batches`() {
        Fixture().use { fixture ->
            fixture.show(*Array(200) { "$URL/$it" })
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(64, fixture.requests.single().size)
            fixture.onEdt {
                assertTrue(fixture.openAt(0))
                assertFalse(fixture.openAt(199))
            }
            repeat(3) { fixture.completeAnalysis() }
            assertTrue(fixture.requests.all { it.size <= 64 })
            assertEquals(200, fixture.requests.sumOf { it.size })
            fixture.onEdt { assertTrue(fixture.openAt(199)) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["binding", "source", "provider"])
    fun `epoch invalidation rejects suspended results and serializes the replacement`(changedEpoch: String) {
        val release = CompletableDeferred<Unit>()
        val requests = ArrayList<SwingHyperlinkDetectionRequest>()
        var providerGeneration = 0L
        val detector =
            object : SwingHyperlinkDetector {
                override val configurationGeneration: Long get() = providerGeneration

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    buildList {
                        requests += request
                        if (requests.size == 1) release.await()
                        add(request.hyperlink(0, 0, URL.length, { true }, uri = URL))
                    }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.worker.runCurrent()
            assertEquals(1, requests.size)
            when (changedEpoch) {
                "binding" -> fixture.onEdt { fixture.controller.reset() }
                "provider" -> providerGeneration++
            }
            fixture.show(URL, historyContentGeneration = if (changedEpoch == "source") 1L else 0L)
            fixture.drainUi()
            assertEquals(1, requests.size, "Suspension must not permit overlapping detector calls")
            release.complete(Unit)
            fixture.completeAnalysis()
            fixture.onEdt { assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache)) }
            fixture.completeAnalysis()
            assertEquals(2, requests.size)
            val first = requests.first()
            val second = requests.last()
            assertEquals(changedEpoch == "binding", first.bindingEpoch != second.bindingEpoch)
            assertEquals(changedEpoch == "source", first.sourceEpoch != second.sourceEpoch)
            assertEquals(changedEpoch == "provider", first.providerEpoch != second.providerEpoch)
            fixture.onEdt {
                val id = fixture.controller.hyperlinkIdAt(0, 0, fixture.cache)
                assertTrue(fixture.controller.openDiscoveredHyperlink(id, fixture.cache))
                assertEquals(URL, fixture.controller.discoveredHyperlink(id, fixture.cache)?.uri)
            }
            assertEquals(1, fixture.publications)
        }
    }

    @Test
    fun `disposal cancels suspended provider work without publishing`() {
        val neverReleased = CompletableDeferred<Unit>()
        var cancelled = false
        val fixture =
            Fixture(
                detector =
                    SwingHyperlinkDetector { _ ->
                        buildList {
                            try {
                                neverReleased.await()
                            } finally {
                                cancelled = true
                            }
                        }
                    },
            )
        fixture.use {
            fixture.show(URL)
            fixture.drainUi()
            fixture.worker.runCurrent()
        }
        assertTrue(cancelled)
        assertEquals(0, fixture.publications)
        assertEquals(0, fixture.worker.pendingTasks)
    }

    @Test
    fun `failure does not become successful empty analysis or trigger busy retries`() {
        var invocations = 0
        Fixture(
            detector =
                SwingHyperlinkDetector { _ ->
                    buildList {
                        if (++invocations == 1) error("Provider failed")
                    }
                },
        ).use { fixture ->
            fixture.show("plain text")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(1, invocations)
            assertEquals(0, fixture.worker.pendingTasks)
            assertEquals(0, fixture.publications)
            fixture.show("plain text")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(2, invocations)
            assertEquals(1, fixture.publications)
            fixture.show("plain text")
            fixture.drainUi()
            fixture.finishSourceScan()
            assertEquals(0, fixture.worker.pendingTasks, "Successful empty results are reusable")
        }
    }

    @Test
    fun `newly visible links are detected without advancing time`() {
        Fixture().use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks)

            fixture.completeAnalysis()

            fixture.onEdt {
                assertTrue(fixture.controller.hyperlinkIdAt(0, 0, fixture.cache) < 0)
                assertTrue(fixture.openAt(0))
            }
            assertEquals(listOf(URL), fixture.opened)
            assertEquals(0L, fixture.uiDispatcher.scheduler.currentTime)
        }
    }

    @Test
    fun `frames arriving during analysis coalesce into the latest pending text`() {
        Fixture().use { fixture ->
            fixture.show("https://old.example")
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks)

            fixture.show("https://intermediate.example")
            fixture.show("https://latest.example")
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks, "Only the original analysis is queued")

            fixture.completeAnalysis()
            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
            }
            assertEquals(1, fixture.worker.pendingTasks, "The latest text is analyzed after completion")
            fixture.completeAnalysis()

            assertEquals(
                listOf(listOf("https://old.example\n"), listOf("https://latest.example\n")),
                fixture.requests,
            )
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf("https://latest.example"), fixture.opened)
        }
    }

    @Test
    fun `completed analysis publishes an unchanged line while another line is updating`() {
        Fixture().use { fixture ->
            fixture.show(URL, "building 10%")
            fixture.drainUi()

            fixture.show(URL, "building 11%")
            fixture.show(URL, "building 12%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.onEdt { assertTrue(fixture.openAt(0), "Unrelated output must not reject this result") }
            assertEquals(listOf(URL), fixture.opened)
            fixture.completeAnalysis()
            assertEquals(listOf("building 12%\n"), fixture.requests.last())
        }
    }

    @Test
    fun `unchanged text reuses both hyperlinks and empty detection results across generations`() {
        Fixture().use { fixture ->
            fixture.show(URL, "no link here")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(1, fixture.requests.size)

            fixture.show(URL, "no link here")
            fixture.drainUi()
            fixture.finishSourceScan()
            assertEquals(0, fixture.worker.pendingTasks)
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(1, fixture.requests.size)

            fixture.show(URL, "still no link here")
            fixture.drainUi()
            fixture.completeAnalysis()
            assertEquals(listOf("still no link here\n"), fixture.requests.last())
        }
    }

    @Test
    fun `scroll reuses visible line results and immediately analyzes newly exposed text`() {
        Fixture().use { fixture ->
            fixture.show(URL, "old bottom", lineIds = longArrayOf(10, 11))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("https://new.example", URL, lineIds = longArrayOf(9, 10))
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            fixture.drainUi()
            assertEquals(1, fixture.worker.pendingTasks)
            fixture.completeAnalysis()

            assertEquals(listOf("https://new.example\n"), fixture.requests.last())
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(URL, "https://new.example"), fixture.opened)
        }
    }

    @Test
    fun `duplicating identityless text keeps each row independently clickable`() {
        Fixture().use { fixture ->
            fixture.show(URL, "other text", lineIds = longArrayOf(0, 0))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show(URL, URL, lineIds = longArrayOf(0, 0))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.onEdt {
                val first = fixture.controller.hyperlinkIdAt(0, 0, fixture.cache)
                val second = fixture.controller.hyperlinkIdAt(1, 0, fixture.cache)
                assertTrue(first < 0, "The first duplicate row must retain its own overlay position")
                assertTrue(second < 0)
                assertNotEquals(first, second)
                assertTrue(fixture.openAt(0))
                assertTrue(fixture.openAt(1))
            }
            assertEquals(listOf(URL, URL), fixture.opened)
        }
    }

    @Test
    fun `identical identityless rows accept their own snapshots without repeated detection`() {
        Fixture().use { fixture ->
            fixture.show(URL, URL, lineIds = longArrayOf(0, 0))
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.onEdt {
                assertTrue(fixture.openAt(0))
                assertTrue(fixture.openAt(1))
                assertNotEquals(
                    fixture.controller.hyperlinkIdAt(0, 0, fixture.cache),
                    fixture.controller.hyperlinkIdAt(1, 0, fixture.cache),
                )
            }
            assertEquals(listOf(URL, URL), fixture.opened)
            fixture.finishSourceScan()
            assertEquals(0, fixture.worker.pendingTasks, "Each exact snapshot has a known current line")
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `explicit URL dependency keeps an existing action usable during percentage changes`() {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("$URL 11%")
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            fixture.drainUi()
            fixture.show("$URL 12%")
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }

            assertEquals(listOf(URL, URL, URL), fixture.opened)
        }
    }

    @Test
    fun `explicit URL dependency accepts an in-flight result after the percentage changes`() {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.show("$URL 11%")
            fixture.show("$URL 12%")
            fixture.completeAnalysis()

            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(URL), fixture.opened)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `in-flight token-scoped results reject changed targets including unpublished edits`(unpublished: Boolean) {
        val gate = CompletableDeferred<Unit>()
        val opened = ArrayList<String>()
        var calls = 0
        val detector =
            SwingHyperlinkDetector { request ->
                calls++
                val links = findUrls(request, opened, validateUrlToken = true)
                gate.await()
                links
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("$URL 10%")
            fixture.completeAnalysis()
            assertEquals(1, calls)
            fixture.show("$URL 11%")
            val changed = "https://changed.example 11%"
            if (unpublished) fixture.changeSourceWithoutPublishing(changed) else fixture.show(changed)
            gate.complete(Unit)
            fixture.completeAnalysis()
            fixture.onEdt { assertFalse(fixture.openAt(0), "A token-scoped result must still match current source") }
            assertTrue(opened.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://example.com/next 11%", "https://example.comx 11%", "https://changed.com 11%"])
    fun `editing URL text or its token boundary immediately invalidates its previous action`(changedText: String) {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show(changedText)

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
            assertTrue(fixture.opened.isEmpty())
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(changedText.substringBefore(' ')), fixture.opened)
        }
    }

    @Test
    fun `URL extension at the previous end of line invalidates a token-scoped action`() {
        Fixture(validateUrlToken = true).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("$URL/next")

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
        }
    }

    @Test
    fun `opaque actions depend on the whole logical line by default`() {
        Fixture().use { fixture ->
            fixture.show("$URL 10%")
            fixture.drainUi()
            fixture.completeAnalysis()

            fixture.show("$URL 11%")

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
        }
    }

    @Test
    fun `reset waits for an executing detector and discards its obsolete result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val opened = ArrayList<String>()
        val detector =
            SwingHyperlinkDetector { request ->
                buildList {
                    val activeCount = active.incrementAndGet()
                    maximumActive.accumulateAndGet(activeCount, ::maxOf)
                    try {
                        val invocation = invocations.incrementAndGet()
                        if (invocation == 1) {
                            entered.countDown()
                            // Deliberately block the dedicated executor to model a synchronous detector during reset.
                            @Suppress("BlockingMethodInNonBlockingContext")
                            assertTrue(release.await(3, TimeUnit.SECONDS), "Test did not release the original detector")
                        }
                        assertEquals("$URL\n", request.lineText(0))
                        add(request.hyperlink(0, 0, URL.length, { opened.add("request $invocation") }))
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        Fixture(detector = detector).use { fixture ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.show(URL)
                fixture.drainUi()
                val original = executor.submit { fixture.worker.runCurrent() }
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Detector did not start")

                fixture.onEdt { fixture.controller.reset() }
                fixture.show(URL)
                fixture.drainUi()
                assertEquals(0, fixture.worker.pendingTasks, "Reset must not overlap a synchronous detector")

                release.countDown()
                original.get(3, TimeUnit.SECONDS)
                fixture.drainUi()
                fixture.onEdt {
                    assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                }
                fixture.completeAnalysis()

                fixture.onEdt { assertTrue(fixture.openAt(0)) }
                assertEquals(listOf("request 2"), opened)
                assertEquals(2, invocations.get())
                assertEquals(1, maximumActive.get())
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `dispose prevents queued results and future frames from restoring links`() {
        Fixture().use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            val publicationsBeforeDispose = fixture.publications
            fixture.onEdt { fixture.controller.dispose() }
            fixture.completeAnalysis()

            fixture.show(URL)
            fixture.drainUi()

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(0, 0, fixture.cache))
                assertFalse(fixture.openAt(0))
            }
            assertEquals(0, fixture.worker.pendingTasks)
            assertEquals(publicationsBeforeDispose, fixture.publications)
        }
    }

    @Test
    fun `cancelled host detection does not continuously reschedule unchanged work`() {
        val invocations = AtomicInteger()
        val detector =
            SwingHyperlinkDetector { request ->
                buildList {
                    if (invocations.incrementAndGet() == 1) throw CancellationException("Host read was cancelled")
                    add(request.hyperlink(0, 0, URL.length, { true }))
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.completeAnalysis()

            assertEquals(0, fixture.worker.pendingTasks, "Cancellation must not create an immediate retry loop")
            assertEquals(1, invocations.get())

            fixture.show("$URL/next")
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(2, invocations.get())
        }
    }

    @Test
    fun `cancelled detection still drains a newer frame that arrived while it was running`() {
        val requests = ArrayList<String>()
        val opened = ArrayList<String>()
        val detector =
            SwingHyperlinkDetector { request ->
                buildList {
                    val url = request.lineText(0).trimEnd('\n')
                    requests += url
                    if (requests.size == 1) throw CancellationException("Host read was cancelled")
                    add(request.hyperlink(0, 0, url.length, { opened.add(url) }))
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show(URL)
            fixture.drainUi()
            fixture.show("$URL/next")

            fixture.completeAnalysis()
            assertEquals(listOf(URL), requests)
            assertEquals(1, fixture.worker.pendingTasks, "Cancellation must not lose the already pending frame")

            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(0)) }
            assertEquals(listOf(URL, "$URL/next"), requests)
            assertEquals(listOf("$URL/next"), opened)
            assertEquals(0, fixture.worker.pendingTasks)
        }
    }

    @Test
    fun `viewport detection retains preceding context when only a later line changes`() {
        val requests = ArrayList<List<String>>()
        val detector =
            viewportDetector { request ->
                buildList {
                    requests += List(request.lineCount) { request.lineText(it) }
                    if (request.lineText(0) == "exception context\n") {
                        add(request.hyperlink(1, 0, 6, { true }))
                    }
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("exception context", "source.kt:10")
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.show("exception context", "source.kt:11")
            fixture.drainUi()
            fixture.completeAnalysis()

            assertEquals(
                listOf(
                    listOf("exception context\n", "source.kt:10\n"),
                    listOf("exception context\n", "source.kt:11\n"),
                ),
                requests,
            )
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
        }
    }

    @Test
    fun `preceding context changes invalidate contextual actions while explicit URL dependencies survive`() {
        val opened = ArrayList<String>()
        val detector =
            viewportDetector { request ->
                buildList {
                    val context = request.lineText(0)
                    add(request.hyperlink(1, 0, 6, { opened.add(context) }))
                    addAll(findUrls(request, opened, validateUrlToken = true))
                }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("old context", "source.kt:10", URL)
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            assertEquals(listOf("old context\n"), opened)
            opened.clear()

            fixture.show("new context", "source.kt:10", URL)

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(1, 0, fixture.cache))
                assertFalse(fixture.openAt(1))
                assertTrue(fixture.openAt(2))
            }
            assertEquals(listOf(URL), opened)
            fixture.drainUi()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            assertEquals(listOf(URL, "new context\n"), opened)
        }
    }

    @Test
    fun `in-flight viewport results reject changed context but accept independent URLs`() {
        val opened = ArrayList<String>()
        val detector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.INDEPENDENT_AND_ORDERED

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    buildList {
                        if (request.context == SwingHyperlinkDetectionContext.INDEPENDENT_LINE) {
                            addAll(findUrls(request, opened, validateUrlToken = true))
                        } else {
                            val context = request.lineText(0)
                            add(request.hyperlink(1, 0, 6, { opened.add(context) }))
                        }
                    }
            }
        Fixture(detector = detector).use { fixture ->
            fixture.show("old context", "source.kt:10", URL)
            fixture.drainUi()
            fixture.show("new context", "source.kt:10", URL)
            fixture.completeAnalysis()

            fixture.onEdt {
                assertEquals(0, fixture.controller.hyperlinkIdAt(1, 0, fixture.cache))
                assertFalse(fixture.openAt(1))
                assertTrue(fixture.openAt(2))
            }
            assertEquals(listOf(URL), opened)
            fixture.completeAnalysis()
            fixture.completeAnalysis()
            fixture.onEdt { assertTrue(fixture.openAt(1)) }
            assertEquals(listOf(URL, "new context\n"), opened)
        }
    }

    private class Fixture(
        validateUrlToken: Boolean = false,
        detector: SwingHyperlinkDetector? = null,
        private var sourceBound: Boolean = true,
    ) : AutoCloseable {
        val cache = TerminalRenderCache(COLUMNS, 1)
        private val source = TerminalHyperlinkTestSource(cache)

        @Volatile var beforeSourceRead: () -> Unit = { assertFalse(SwingUtilities.isEventDispatchThread()) }
        private val reader =
            object : TerminalRenderFrameReader by source {
                override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                    beforeSourceRead()
                    source.readRenderFrame(consumer)
                }

                override fun readRenderFrameForAbsoluteRange(
                    startAbsoluteRow: Long,
                    endAbsoluteRow: Long,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    beforeSourceRead()
                    source.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer)
                }
            }
        val uiDispatcher = StandardTestDispatcher()
        val worker = QueuedDispatcher()
        val opened = ArrayList<String>()
        val requests = ArrayList<List<String>>()
        var publications = 0
            private set
        private var frameGeneration = 0L
        private val completedDetections = AtomicInteger()
        private var sourcePrepared = false
        val failures = ConcurrentLinkedQueue<Throwable>()
        private val scope =
            CoroutineScope(
                SupervisorJob() + uiDispatcher + CoroutineExceptionHandler { _, failure -> failures.add(failure) },
            )
        private val delegate =
            detector ?: SwingHyperlinkDetector { request ->
                requests += List(request.lineCount) { request.lineText(it) }
                findUrls(request, opened, validateUrlToken)
            }

        private fun observed(detector: SwingHyperlinkDetector): SwingHyperlinkDetector =
            object : SwingHyperlinkDetector by detector {
                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> =
                    try {
                        detector.detect(request)
                    } finally {
                        completedDetections.incrementAndGet()
                    }
            }

        private var currentDetector = observed(delegate)
        private val host =
            object : TerminalHyperlinkDiscoveryHost {
                override val renderCache = cache
                override val hyperlinkSource get() = reader.takeIf { sourceBound }
                override val hyperlinkDetector get() = currentDetector

                override fun hyperlinksChanged() {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    publications++
                }

                override fun repaintHyperlinkSpan(
                    startRow: Int,
                    startColumn: Int,
                    endRow: Int,
                    endColumn: Int,
                ) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                }
            }
        val controller = TerminalHyperlinkDiscoveryController(host, scope, worker)

        fun show(
            vararg lines: String,
            lineIds: LongArray = LongArray(lines.size) { it + 1L },
            historyContentGeneration: Long = 0L,
            contentGeneration: Long? = null,
        ) {
            onEdt {
                val generation = ++frameGeneration
                source.publish(TextFrame(generation, lines, lineIds, historyContentGeneration, contentGeneration ?: generation))
                controller.scheduleForFrame()
            }
            if (!sourcePrepared) {
                // Start tests at the detector boundary with the initial source batch already copied.
                drainUi()
                worker.runCurrent()
                drainUi()
                sourcePrepared = true
            }
        }

        fun openAt(row: Int): Boolean = controller.openDiscoveredHyperlink(controller.hyperlinkIdAt(row, 0, cache), cache)

        fun changeSourceWithoutPublishing(vararg lines: String) =
            onEdt {
                source.publish(TextFrame(++frameGeneration, lines, LongArray(lines.size) { it + 1L }, 0L), publishViewport = false)
            }

        fun bindSource(bound: Boolean) =
            onEdt {
                sourceBound = bound
                controller.reconcile()
            }

        fun replaceDetector(detector: SwingHyperlinkDetector) =
            onEdt {
                currentDetector = observed(detector)
                controller.scheduleForFrame()
            }

        fun drainUi() = onEdt { uiDispatcher.scheduler.runCurrent() }

        fun completeAnalysis() {
            val before = completedDetections.get()
            // Drain controlled copy/replay phases until this detector batch returns or suspends.
            do {
                worker.runCurrent()
                drainUi()
            } while (completedDetections.get() == before && worker.pendingTasks > 0)
            // A returned detector batch still needs source validation before EDT publication.
            worker.runCurrent()
            drainUi()
        }

        fun finishSourceScan() {
            val before = completedDetections.get()
            completeAnalysis()
            assertEquals(before, completedDetections.get(), "Copying unchanged source must reuse its detector results")
        }

        fun onEdt(action: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
        }

        override fun close() {
            onEdt {
                controller.dispose()
                scope.cancel()
            }
            worker.runCurrent()
            drainUi()
            assertTrue(failures.isEmpty(), "Unexpected coroutine failures: $failures")
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        val pendingTasks: Int
            get() = queue.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
        }

        fun runCurrent() {
            while (true) {
                val next = queue.poll() ?: return
                next.run()
            }
        }
    }

    private class TextFrame(
        override val frameGeneration: Long,
        private val lines: Array<out String>,
        private val lineIds: LongArray,
        override val historyContentGeneration: Long,
        override val contentGeneration: Long = frameGeneration,
    ) : TerminalRenderFrame {
        override val structureGeneration = 1L
        override val historySize = (lineIds.firstOrNull()?.minus(1L) ?: 0L).coerceAtLeast(0L).toInt()
        override val columns = COLUMNS
        override val rows = lines.size
        override val activeBuffer = TerminalRenderBufferKind.PRIMARY
        override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 0)

        override fun lineGeneration(row: Int): Long = frameGeneration

        override fun lineId(row: Int): Long = lineIds[row]

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            val text = lines[row]
            for (column in 0 until columns) {
                codeWords[codeOffset + column] = text.getOrNull(column)?.code ?: 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = if (column < text.length) TerminalRenderCellFlags.CODEPOINT else 0
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
            }
        }
    }

    private companion object {
        private const val COLUMNS = 64
        private const val URL = "https://example.com"

        private fun viewportDetector(onDetect: suspend (SwingHyperlinkDetectionRequest) -> List<SwingHyperlink>): SwingHyperlinkDetector =
            object : SwingHyperlinkDetector {
                override val context = SwingHyperlinkDetectionContext.ORDERED_CONTENT

                override suspend fun detect(request: SwingHyperlinkDetectionRequest): List<SwingHyperlink> = onDetect(request)
            }

        private fun findUrls(
            request: SwingHyperlinkDetectionRequest,
            opened: MutableList<String>,
            validateUrlToken: Boolean,
        ): List<SwingHyperlink> =
            buildList {
                assertFalse(SwingUtilities.isEventDispatchThread(), "Host detection must stay outside the EDT")
                for (lineIndex in 0 until request.lineCount) {
                    val text = request.lineText(lineIndex)
                    if (!text.startsWith("https://")) continue
                    val url = text.takeWhile { !it.isWhitespace() }
                    add(
                        request.hyperlink(
                            lineIndex = lineIndex,
                            startOffset = 0,
                            endOffset = url.length,
                            action = { opened.add(url) },
                            validationStartOffset = 0,
                            validationEndOffset = if (validateUrlToken) url.length + 1 else Int.MAX_VALUE,
                        ),
                    )
                }
            }
    }
}
