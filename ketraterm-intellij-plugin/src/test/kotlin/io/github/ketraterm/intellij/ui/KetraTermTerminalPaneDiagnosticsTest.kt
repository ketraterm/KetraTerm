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
package io.github.ketraterm.intellij.ui

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class KetraTermTerminalPaneDiagnosticsTest {
    @Test
    fun `non coroutine platform control flow retains its cause at the coroutine boundary`() {
        val controlFlow = object : RuntimeException("platform control flow"), ControlFlowException {}
        val propagated =
            assertThrows(CancellationException::class.java) {
                KetraTermTerminalPane.reportShellSuggestionFailure(controlFlow)
            }
        assertSame(controlFlow, propagated.cause)
    }

    @Test
    fun `platform cancellation propagates unchanged`() {
        val cancellation = ProcessCanceledException()
        val propagated =
            assertThrows(CancellationException::class.java) {
                KetraTermTerminalPane.reportShellSuggestionFailure(cancellation)
            }
        assertSame(cancellation, propagated)
    }

    @Test
    fun `coroutine cancellation propagates unchanged`() {
        val cancellation = CancellationException("cancelled")
        val propagated =
            assertThrows(CancellationException::class.java) {
                KetraTermTerminalPane.reportShellSuggestionFailure(cancellation)
            }
        assertSame(cancellation, propagated)
    }
}
