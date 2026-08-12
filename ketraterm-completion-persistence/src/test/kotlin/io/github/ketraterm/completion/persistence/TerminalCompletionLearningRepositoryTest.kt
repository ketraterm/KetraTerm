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

import io.github.ketraterm.completion.api.TerminalCompletionSources
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionLearningRepositoryTest {
    @Test
    fun `mutation is persisted through one suspending repository`() =
        runTest {
            val path = createTempDirectory("completion-learning").resolve(TerminalCompletionStatsStore.currentFileName())
            val learning = TerminalCompletionSources.learningStore()
            val repository =
                TerminalCompletionLearningRepository(
                    learningStore = learning,
                    initialPersistencePath = path,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            repository.initialize()
            repository.mutate {
                recordCommandResult("git status", true, null, null, 42L)
            }

            assertEquals(learning.snapshotAll(), TerminalCompletionStatsStore(path).loadSnapshot())
        }
}
