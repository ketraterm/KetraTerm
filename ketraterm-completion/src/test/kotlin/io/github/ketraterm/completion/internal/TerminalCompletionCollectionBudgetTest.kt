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
package io.github.ketraterm.completion.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionCollectionBudgetTest {
    @Test
    fun `small final limit receives bounded reranking surplus`() {
        assertEquals(32, TerminalCompletionCollectionBudget.forFinalLimit(8))
    }

    @Test
    fun `large final limit caps additional collection work`() {
        assertEquals(1_256, TerminalCompletionCollectionBudget.forFinalLimit(1_000))
    }

    @Test
    fun `maximum integer final limit does not overflow`() {
        assertEquals(Int.MAX_VALUE, TerminalCompletionCollectionBudget.forFinalLimit(Int.MAX_VALUE))
    }
}
