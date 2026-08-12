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

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionLearningWorkerTest {
    @Test
    fun `close drains transactions before final cleanup`() {
        val events = ArrayList<String>()
        val worker = TerminalCompletionLearningWorker("completion-learning-test")

        worker.submit { events += "first" }
        worker.submit { events += "second" }
        worker.close { events += "close" }

        assertEquals(listOf("first", "second", "close"), events)
    }

    @Test
    fun `close and late submissions are idempotent`() {
        val events = ArrayList<String>()
        val worker = TerminalCompletionLearningWorker("completion-learning-test")

        worker.close { events += "close" }
        worker.submit { events += "late" }
        worker.close { events += "duplicate-close" }

        assertEquals(listOf("close"), events)
    }
}
