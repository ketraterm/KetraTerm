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
package io.github.ketraterm.intellij.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class KetraTermCompletionServiceTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    fun testResetBeforeOpeningATerminalClearsSavedDataWithSuggestionsDisabled() = verifyReset(suggestionsEnabled = false)

    fun testResetBeforeOpeningATerminalClearsSavedDataWithSuggestionsEnabled() = verifyReset(suggestionsEnabled = true)

    private fun verifyReset(suggestionsEnabled: Boolean) =
        runBlocking {
            val settings = KetraTermIntellijSettings.getInstance()
            val original = settings.state
            val path = Path.of(myFixture.tempDirFixture.tempDirPath).resolve(TerminalCompletionLearningCoordinator.currentFileName())
            snapshot(path, seed = true)
            assertFalse(snapshot(path).replayCommands.isEmpty())
            settings.replaceState(original.copy(smartSuggestionsEnabled = suggestionsEnabled, completionLearningPersistenceEnabled = false))
            val service = KetraTermCompletionService(path)
            var providerChanges = 0
            service.addProviderListener { providerChanges++ }
            try {
                val first = service.resetLearning()
                val second = service.resetLearning()
                settings.replaceState(settings.state.copy(smartSuggestionsEnabled = !suggestionsEnabled))
                withTimeout(10.seconds) {
                    first.await()
                    second.await()
                }
                UIUtil.dispatchAllInvocationEvents()
                assertTrue(providerChanges > 0)
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, snapshot(path))
                settings.replaceState(settings.state.copy(smartSuggestionsEnabled = true, completionLearningPersistenceEnabled = true))
                withTimeout(10.seconds) { service.resetLearning().await() }
                UIUtil.dispatchAllInvocationEvents()
                assertEquals(TerminalCompletionLearningSnapshot.EMPTY, snapshot(path))
            } finally {
                service.dispose()
                settings.replaceState(original)
            }
        }

    private suspend fun snapshot(
        path: Path,
        seed: Boolean = false,
    ): TerminalCompletionLearningSnapshot =
        coroutineScope {
            val learning = TerminalCompletionLearningStore()
            var loadFailure: Throwable? = null
            val coordinator =
                TerminalCompletionLearningCoordinator(
                    learningStore = learning,
                    coroutineScope = this,
                    persistencePath = path,
                    persistenceEnabled = true,
                    onPersistenceLoadFailure = { loadFailure = it },
                )
            if (seed) coordinator.recordCommandResult("git status", true, null, null, 1L)
            coordinator.closeAndFlush()
            check(loadFailure == null) { "Cannot load learning: $loadFailure" }
            learning.snapshot()
        }
}
