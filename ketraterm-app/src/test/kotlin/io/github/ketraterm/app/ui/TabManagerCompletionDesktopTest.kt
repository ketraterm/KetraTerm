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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.config.KetraTermConfig
import io.github.ketraterm.app.config.KetraTermConfigManager
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.TerminalCompletionLearningSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.workspace.TerminalProfile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.CardLayout
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

@Tag("desktop")
class TabManagerCompletionDesktopTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reset before opening a terminal clears saved learning and survives preference changes`(
        suggestionsEnabled: Boolean,
        @TempDir directory: Path,
    ) = runBlocking {
        val config = KetraTermConfigManager(directory.resolve("config.toml"))
        config.save(KetraTermConfig(smartSuggestionsEnabled = suggestionsEnabled, persistentSuggestionLearningEnabled = false))
        val settings = KetraTermSettings(config)
        val path = settings.commandCompletionStatsPath
        snapshot(path, seed = true)
        assertFalse(snapshot(path).replayCommands.isEmpty())
        val (frame, manager) =
            onEdt {
                val frame = JFrame()
                val content = JPanel(CardLayout())
                frame.contentPane = content
                frame.pack()
                frame to
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        content,
                        settings,
                        { TerminalProfile("test", "Test", listOf("unused")) },
                    )
            }
        try {
            val resets = onEdt { listOf(manager.resetCompletionLearning(), manager.resetCompletionLearning()) }
            settings.update(settings.config.copy(smartSuggestionsEnabled = !suggestionsEnabled))
            withTimeout(10.seconds) { resets.forEach { it.await() } }
            assertEquals(TerminalCompletionLearningSnapshot.EMPTY, snapshot(path))
            settings.update(settings.config.copy(smartSuggestionsEnabled = true, persistentSuggestionLearningEnabled = true))
            withTimeout(10.seconds) { onEdt { manager.resetCompletionLearning() }.await() }
        } finally {
            val closed = CompletableFuture<Unit>()
            onEdt {
                manager.closeAllTabsWithoutConfirmation { result -> result.fold(closed::complete, closed::completeExceptionally) }
                frame.dispose()
            }
            closed.get(10, TimeUnit.SECONDS)
        }
        assertEquals(TerminalCompletionLearningSnapshot.EMPTY, snapshot(path))
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

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
