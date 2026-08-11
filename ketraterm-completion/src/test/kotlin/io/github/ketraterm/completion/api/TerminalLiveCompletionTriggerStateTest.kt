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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCommandSpecs
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalLiveCompletionTriggerStateTest {
    @Test
    fun `eligible state requests once then keeps identical state`() {
        val state = triggerState()

        assertEquals(TerminalLiveCompletionTriggerDecision.REQUEST, state.evaluate("git sw", 6, 6, 0, "file:///repo"))
        assertEquals(TerminalLiveCompletionTriggerDecision.KEEP, state.evaluate("git sw", 6, 6, 0, "file:///repo"))
    }

    @Test
    fun `ranking context change requests the same visible command again`() {
        val state = triggerState()
        state.evaluate("git sw", 6, 6, 0, "file:///repo-a")

        assertEquals(TerminalLiveCompletionTriggerDecision.REQUEST, state.evaluate("git sw", 6, 6, 0, "file:///repo-b"))
    }

    @Test
    fun `ineligible state hides and clears request identity`() {
        val state = triggerState()
        state.evaluate("git sw", 6, 6, 0, "file:///repo")

        assertEquals(TerminalLiveCompletionTriggerDecision.HIDE, state.evaluate(null, 0, 0, 0, "file:///repo"))
        assertEquals(TerminalLiveCompletionTriggerDecision.REQUEST, state.evaluate("git sw", 6, 6, 0, "file:///repo"))
    }

    @Test
    fun `invalidated state requests again without allocating a snapshot key`() {
        val state = triggerState()
        state.evaluate("git sw", 6, 6, 0, "file:///repo")
        state.invalidate()

        assertEquals(TerminalLiveCompletionTriggerDecision.REQUEST, state.evaluate("git sw", 6, 6, 0, "file:///repo"))
    }

    private fun triggerState(): TerminalLiveCompletionTriggerState =
        TerminalLiveCompletionTriggerState(
            minimumNonWhitespaceCharacters = 2,
            commandSpecs = TerminalCommandSpecs.defaults(),
            shellCapabilities = TerminalShellCapabilities.POSIX,
        )
}
