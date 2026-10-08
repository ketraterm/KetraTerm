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
package io.github.ketraterm.benchmark

import io.github.ketraterm.ui.swing.suggestion.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Measures public suggestion interaction operations with EDT dispatch amortized across each batch.
 * Selection reuses one candidate publication; progressive publication copies prebuilt rankings and
 * retains selection. Acceptance includes construction, publication, host admission, feedback, and
 * closure. Provider work, session encoding, popup layout, and painting are outside the measured scope.
 */
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TerminalSuggestionInteractionBenchmark {
    @Param("8", "128")
    var candidateCount = 0

    private val request = SwingShellSuggestionRequest("git st", 6)
    private lateinit var candidates: List<SwingShellSuggestion>
    private lateinit var reversedCandidates: List<SwingShellSuggestion>
    private lateinit var selection: SwingShellSuggestionInteraction
    private lateinit var selectionPublication: SwingShellSuggestionSnapshot
    private lateinit var progressive: SwingShellSuggestionInteraction
    private var selectionIndex = 0
    private var reverseRanking = false
    private var selectedIndex = 0
    private var publishedSelectionIndex = 0
    private var admissionCount = 0L
    private var feedbackCount = 0L
    private var lifecycleCount = 0L
    private val handler =
        SwingShellSuggestionHandler {
            admissionCount++
            SwingShellSuggestionAcceptanceResult.ACCEPTED
        }
    private val feedbackHandler =
        SwingShellSuggestionFeedbackHandler {
            feedbackCount++
        }
    private val selectionBatch =
        Runnable {
            repeat(OPERATIONS_PER_BATCH) {
                selectionIndex = (selectionIndex + 1) % candidateCount
                selection.select(selectionPublication, selectionIndex)
            }
            selectedIndex = selection.snapshot.selectedIndex
        }
    private val publicationBatch =
        Runnable {
            repeat(OPERATIONS_PER_BATCH) {
                reverseRanking = !reverseRanking
                progressive.publish(if (reverseRanking) reversedCandidates else candidates)
            }
            publishedSelectionIndex = progressive.snapshot.selectedIndex
        }
    private val acceptanceBatch =
        Runnable {
            repeat(OPERATIONS_PER_BATCH) {
                val interaction = SwingShellSuggestionInteraction(request, handler, feedbackHandler)
                interaction.publish(candidates, selectedIndex = 0)
                interaction.tryAccept(interaction.snapshot, 0)
                lifecycleCount++
            }
        }

    @Setup
    open fun setup() {
        candidates =
            List(candidateCount) { index ->
                SwingShellSuggestion("status-$index", 4, 6, "benchmark", "SUBCOMMAND")
            }
        reversedCandidates = candidates.reversed()
        SwingUtilities.invokeAndWait {
            selection = SwingShellSuggestionInteraction(request)
            selection.publish(candidates, selectedIndex = 0)
            selectionPublication = selection.snapshot
            progressive = SwingShellSuggestionInteraction(request)
            progressive.publish(candidates, selectedIndex = 0)
        }
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_BATCH)
    open fun selectWithinPublicationOnEdt(): Int {
        SwingUtilities.invokeAndWait(selectionBatch)
        return selectedIndex
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_BATCH)
    open fun publishProgressiveRankingOnEdt(): Int {
        SwingUtilities.invokeAndWait(publicationBatch)
        return publishedSelectionIndex
    }

    @Benchmark
    @OperationsPerInvocation(OPERATIONS_PER_BATCH)
    open fun acceptCompleteInteractionOnEdt(): Long {
        SwingUtilities.invokeAndWait(acceptanceBatch)
        return feedbackCount
    }

    @TearDown
    open fun tearDown() {
        SwingUtilities.invokeAndWait {
            check(selectionPublication.suggestions === selection.snapshot.suggestions)
            check(admissionCount == lifecycleCount && feedbackCount == lifecycleCount)
            selection.close()
            progressive.close()
        }
    }

    private companion object {
        const val OPERATIONS_PER_BATCH = 1024
    }
}
