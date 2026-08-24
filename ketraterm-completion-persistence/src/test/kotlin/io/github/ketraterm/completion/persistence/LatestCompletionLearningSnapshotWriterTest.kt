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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LatestCompletionLearningSnapshotWriterTest {
    @Test
    fun `generation invalidated during materialization never reaches IO`() =
        runTest {
            val persisted = mutableListOf<CompletionLearningSnapshotWrite>()
            lateinit var writer: LatestCompletionLearningSnapshotWriter
            writer =
                LatestCompletionLearningSnapshotWriter(
                    coroutineScope = this,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    debounceMillis = 0L,
                    materialize = { request ->
                        writer.request(null)
                        CompletionLearningSnapshotWrite(request.path, TerminalCommandCompletionStatsSnapshot.EMPTY)
                    },
                    persist = persisted::add,
                )

            writer.request(CompletionLearningWriteRequest(Path("learning.tsv")))
            runCurrent()
            writer.flushLatest()

            assertTrue(persisted.isEmpty())
            writer.closeAndFlush()
        }
}
