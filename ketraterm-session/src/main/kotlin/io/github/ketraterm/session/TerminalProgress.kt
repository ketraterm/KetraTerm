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
package io.github.ketraterm.session

import io.github.ketraterm.protocol.TerminalProgressState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Immutable application-controlled OSC 9;4 progress presentation.
 *
 * [percentage] is inclusive 0..100. HIDDEN and INDETERMINATE have canonical
 * percentage zero; zero is a visible value for NORMAL, ERROR and WARNING.
 * A value of 100 does not clear progress: the application must explicitly hide it.
 * These are presentation hints, not authenticated process or command status.
 *
 * @throws IllegalArgumentException for percentages outside 0..100, or a nonzero
 * percentage with HIDDEN or INDETERMINATE.
 */
public data class TerminalProgress(
    public val state: TerminalProgressState,
    public val percentage: Int,
) {
    init {
        require(percentage in 0..100) { "percentage must be in 0..100" }
        require(percentage == 0 || (state != TerminalProgressState.HIDDEN && state != TerminalProgressState.INDETERMINATE)) {
            "Hidden and indeterminate progress must have percentage zero"
        }
    }

    /** Canonical absent progress, reused for clearing and expiry. */
    public companion object {
        public val HIDDEN: TerminalProgress = TerminalProgress(TerminalProgressState.HIDDEN, 0)
    }
}

/** One atomic report authority and one lazy expiry worker; no monitor surrounds subscriber resumption. */
internal class TerminalProgressTracker(
    scope: CoroutineScope,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private class Report(
        val progress: TerminalProgress,
        val updatedAtNanos: Long,
        val closed: Boolean = false,
    )

    private val reports = MutableStateFlow(Report(TerminalProgress.HIDDEN, 0L))

    // Projection preserves synchronous value reads without exposing freshness-only reports.
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val progress: StateFlow<TerminalProgress> =
        object : StateFlow<TerminalProgress> {
            override val value: TerminalProgress get() = reports.value.progress
            override val replayCache: List<TerminalProgress> get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<TerminalProgress>): Nothing {
                reports.map { it.progress }.distinctUntilChanged().collect(collector)
                awaitCancellation()
            }
        }

    private val expiryJob =
        scope
            .launch(start = CoroutineStart.LAZY, context = CoroutineName("terminal-progress-expiry")) {
                reports.collectLatest { report ->
                    if (report.closed || report.progress.state == TerminalProgressState.HIDDEN) return@collectLatest
                    val elapsed = (nanoTime() - report.updatedAtNanos).coerceAtLeast(0)
                    delay((STALE_TIMEOUT_NANOS - elapsed).coerceAtLeast(0).nanoseconds)
                    // Exact receipt identity retires old deadlines, including identical repeated reports.
                    reports.compareAndSet(report, Report(TerminalProgress.HIDDEN, report.updatedAtNanos))
                }
            }.apply {
                // Also runs when the owner cancels before this lazy worker ever starts.
                invokeOnCompletion { retire() }
            }

    fun update(
        state: TerminalProgressState,
        percentage: Int,
    ) {
        reports.update { previous ->
            if (previous.closed) {
                previous
            } else {
                val current = previous.progress
                val next =
                    when {
                        state == TerminalProgressState.HIDDEN -> TerminalProgress.HIDDEN
                        current.state == state && current.percentage == percentage -> current
                        else -> TerminalProgress(state, percentage)
                    }
                Report(next, nanoTime())
            }
        }
        if (state != TerminalProgressState.HIDDEN && !reports.value.closed) expiryJob.start()
    }

    fun clear() = update(TerminalProgressState.HIDDEN, 0)

    override fun close() {
        retire()
        expiryJob.cancel()
    }

    private fun retire() {
        reports.update { previous ->
            if (previous.closed) {
                previous
            } else {
                Report(TerminalProgress.HIDDEN, previous.updatedAtNanos, closed = true)
            }
        }
    }

    private companion object {
        const val STALE_TIMEOUT_NANOS = 300_000_000_000L
    }
}
