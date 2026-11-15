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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalShellIntegrationView
import kotlinx.coroutines.*

/** EDT-owned demand with one serialized worker and detached, generation-checked projections. */
internal class PromptDecorationAnalysis(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onReady: () -> Unit,
) {
    internal data class Request(
        val reader: TerminalRenderFrameReader,
        val state: TerminalShellIntegrationView,
        val contentGeneration: Long,
        val historyGeneration: Long,
        val revision: Long,
        val visibleRows: Int,
        val retainedOutput: Boolean,
        val anchorLineId: Long,
        val promptLineId: Long,
    )

    private val workerLayout = PromptDividerLayout()
    private var demand: Request? = null
    private var pending: Request? = null
    private var job: Job? = null
    private var epoch = 0L
    private var completed: Request? = null
    private var projection: PromptDividerLayout? = null

    fun projection(request: Request): PromptDividerLayout? = projection.takeIf { completed == request }

    fun request(request: Request) {
        if (demand == request) return
        demand = request
        pending = request
        if (job != null || !scope.isActive) return
        val next =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (isActive) {
                        val current = pending ?: break
                        pending = null
                        val generation = epoch
                        val result =
                            withContext(dispatcher) {
                                workerLayout.updateFrom(
                                    current.reader,
                                    current.state,
                                    current.visibleRows,
                                    current.retainedOutput,
                                    current.anchorLineId,
                                    current.promptLineId,
                                )
                                if (workerLayout.contentGeneration == current.contentGeneration &&
                                    workerLayout.historyGeneration == current.historyGeneration &&
                                    current.state.revision.value == current.revision
                                ) {
                                    workerLayout.snapshot()
                                } else {
                                    null
                                }
                            }
                        if (epoch == generation && demand == current && result != null) {
                            completed = current
                            projection = result
                            onReady()
                        }
                    }
                } finally {
                    job = null
                }
            }
        job = next
        next.start()
    }

    /** A blocked old reader may finish, but cannot publish or overlap replacement analysis. */
    fun reset() {
        epoch++
        demand = null
        pending = null
        completed = null
        projection = null
    }
}
