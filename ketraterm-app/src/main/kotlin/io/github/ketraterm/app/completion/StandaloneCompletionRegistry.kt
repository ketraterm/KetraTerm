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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import kotlinx.coroutines.*
import java.nio.file.Path

/**
 * Standalone completion wiring for one application window.
 *
 * The registry composes standalone path completion with shared learned-command
 * ranking and owns persistence for the same application lifecycle.
 *
 * @param persistencePath fixed product-owned learning destination.
 * @param persistenceEnabled whether the fixed destination may initially be read and written.
 * @param specs static command specs shared by providers created from this registry.
 * @param learningStore bounded learning shared by ranking and persistence.
 */
internal class StandaloneCompletionRegistry private constructor(
    persistencePath: Path,
    persistenceEnabled: Boolean,
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    learningStore: TerminalCompletionLearningStore = TerminalCompletionLearningStore(),
    internal val completionScope: CoroutineScope,
) {
    private val lifecycleLock = Any()
    private var closed = false
    private val commandSpecs = specs
    private val learningStore = learningStore
    private val learning =
        TerminalCompletionLearningCoordinator(
            learningStore = learningStore,
            coroutineScope = completionScope,
            persistencePath = persistencePath,
            persistenceEnabled = persistenceEnabled,
        )
    private val feedbackRecorder =
        SwingCompletionFeedbackRecorder(
            recordSuggestionFeedback = { commandLine, feedback, profileId, workingDirectoryUri, feedbackAtEpochMillis ->
                synchronized(lifecycleLock) {
                    if (!closed) {
                        learning.recordSuggestionFeedback(
                            commandLine = commandLine,
                            feedback = feedback,
                            profileId = profileId,
                            workingDirectoryUri = workingDirectoryUri,
                            feedbackAtEpochMillis = feedbackAtEpochMillis,
                        )
                    }
                }
            },
        )

    /**
     * Creates completion resources for one standalone terminal pane.
     *
     * The returned provider reads [workingDirectoryUriProvider] every time
     * suggestions are requested so ranking can react to OSC 7 directory updates
     * without rebuilding the provider.
     *
     * @param profileId stable standalone profile id for this session.
     * @param shellCapabilities shell lexical and replacement rules selected from the profile.
     * @param workingDirectoryUriProvider supplier for the latest current-working-directory URI.
     * @return provider and feedback resources for the pane.
     * @throws IllegalStateException if this registry is closed.
     */
    fun createResources(
        profileId: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
        workingDirectoryUriProvider: () -> String? = { null },
    ): StandaloneCompletionResources =
        synchronized(lifecycleLock) {
            check(!closed) { "standalone completion registry is closed" }
            val contextProvider = {
                SwingCompletionContext(
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUriProvider(),
                    shellCapabilities = shellCapabilities,
                )
            }
            val engine =
                TerminalCompletionEngines.fromSources(
                    sources =
                        listOf(
                            TerminalCompletionSourceEntry(
                                TerminalCompletionSources.path(TerminalLocalFileSystemProvider()),
                                TerminalCompletionSourcePrior.DIRECTORY_PATH,
                            ),
                        ),
                    commandSpecs = commandSpecs,
                    learningStore = learningStore,
                )
            StandaloneCompletionResources(
                provider = SwingCompletionSuggestionProvider(engine, contextProvider),
                feedbackHandler = feedbackRecorder.createHandler(contextProvider),
            )
        }

    /**
     * Records one completed command in shared learning.
     *
     * @param commandLine command text captured from shell integration metadata.
     * @param successful whether the command completed successfully.
     * @param profileId profile id active when the command ran.
     * @param workingDirectoryUri current-working-directory URI captured at command start.
     * @param usedAtEpochMillis non-negative completion timestamp.
     */
    fun recordFinishedCommand(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        synchronized(lifecycleLock) {
            if (closed) return
            learning.recordCommandResult(
                commandLine = commandLine,
                successful = successful,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
                usedAtEpochMillis = usedAtEpochMillis,
            )
        }
    }

    /**
     * Changes whether the fixed learning destination may be read and written.
     *
     * @param enabled `true` to persist sanitized learning across application restarts.
     */
    fun setPersistenceEnabled(enabled: Boolean) {
        synchronized(lifecycleLock) {
            if (!closed) learning.setPersistenceEnabled(enabled)
        }
    }

    /** Stops accepting learning events and waits for the final dirty persistence write. */
    suspend fun closeAndFlush() {
        synchronized(lifecycleLock) {
            closed = true
        }
        try {
            learning.closeAndFlush()
        } finally {
            completionScope.cancel()
        }
    }

    internal companion object {
        fun create(
            persistencePath: Path,
            persistenceEnabled: Boolean,
            specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
            learningStore: TerminalCompletionLearningStore = TerminalCompletionLearningStore(),
        ): StandaloneCompletionRegistry {
            val completionScope =
                CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("standalone-completion"))
            return try {
                StandaloneCompletionRegistry(
                    persistencePath = persistencePath,
                    persistenceEnabled = persistenceEnabled,
                    specs = specs,
                    learningStore = learningStore,
                    completionScope = completionScope,
                )
            } catch (failure: Throwable) {
                completionScope.cancel()
                throw failure
            }
        }
    }
}

/**
 * Immutable completion resources consumed by one standalone terminal pane.
 *
 * @property provider popup-facing suggestion provider.
 * @property feedbackHandler acceptance and dismissal learning handler.
 */
internal data class StandaloneCompletionResources(
    val provider: SwingShellSuggestionProvider,
    val feedbackHandler: SwingShellSuggestionFeedbackHandler,
)
