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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.api.TerminalCompletionSessionRegistry
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Standalone completion wiring for one application window.
 *
 * The registry supplies standalone context and local-file access around the
 * shared [TerminalCompletionSessionRegistry], and owns learned-command
 * persistence for the same application lifecycle.
 *
 * @param persistencePath fixed product-owned learning destination.
 * @param persistenceEnabled whether the fixed destination may initially be read and written.
 * @param coroutineScope host lifecycle scope that parents persistence work.
 * @param specs static command specs shared by providers created from this registry.
 * @param learningStore bounded learning shared by ranking and persistence.
 * @param sessionMruCapacity maximum distinct commands retained per terminal session.
 */
internal class StandaloneCompletionRegistry(
    persistencePath: Path,
    persistenceEnabled: Boolean,
    coroutineScope: CoroutineScope,
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    learningStore: TerminalCompletionLearningStore = TerminalCompletionLearningStore(),
    sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
) {
    private val sessions =
        TerminalCompletionSessionRegistry(
            commandSpecs = specs,
            learningStore = learningStore,
            sessionMruCapacity = sessionMruCapacity,
        )
    private val learning =
        TerminalCompletionLearningCoordinator(
            learningStore = learningStore,
            coroutineScope = coroutineScope,
            persistencePath = persistencePath,
            persistenceEnabled = persistenceEnabled,
        )
    private val feedbackRecorder =
        SwingCompletionFeedbackRecorder(
            recordSuggestionFeedback = learning::recordSuggestionFeedback,
        )

    /**
     * Creates a standalone Swing suggestion provider for one terminal session.
     *
     * The returned provider reads [workingDirectoryUriProvider] every time
     * suggestions are requested so ranking can react to OSC 7 directory updates
     * without rebuilding the provider.
     *
     * @param sessionId stable workspace tab/session id.
     * @param profileId stable standalone profile id for this session.
     * @param shellCapabilities shell lexical and replacement rules selected from the profile.
     * @param workingDirectoryUriProvider supplier for the latest current-working-directory URI.
     * @return standalone Swing suggestion provider for the session.
     * @throws IllegalStateException if this registry is closed.
     */
    fun createProvider(
        sessionId: String,
        profileId: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
        workingDirectoryUriProvider: () -> String? = { null },
    ): SwingCompletionSuggestionProvider {
        val session = sessions.openSession(sessionId, TerminalLocalFileSystemProvider())
        return try {
            SwingCompletionSuggestionProvider(
                engine = session.engine,
                contextProvider = {
                    SwingCompletionContext(
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUriProvider(),
                        shellCapabilities = shellCapabilities,
                    )
                },
            )
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    /**
     * Creates a feedback handler whose exact learning is recorded immediately.
     *
     * @param profileId stable standalone profile id for this session.
     * @param workingDirectoryUriProvider supplier for the latest current-working-directory URI.
     * @return feedback handler for accepted and dismissed suggestions.
     */
    fun createFeedbackHandler(
        profileId: String?,
        workingDirectoryUriProvider: () -> String?,
    ): SwingShellSuggestionFeedbackHandler =
        feedbackRecorder.createHandler {
            SwingCompletionContext(
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUriProvider(),
            )
        }

    /**
     * Records one completed command in global learning and, on success, the session MRU.
     *
     * @param sessionId workspace tab/session id that produced the command.
     * @param commandLine command text captured from shell integration metadata.
     * @param successful whether the command completed successfully.
     * @param profileId profile id active when the command ran.
     * @param workingDirectoryUri current-working-directory URI captured at command start.
     * @param usedAtEpochMillis non-negative completion timestamp.
     */
    fun recordFinishedCommand(
        sessionId: String,
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        learning.recordCommandResult(
            commandLine = commandLine,
            successful = successful,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            usedAtEpochMillis = usedAtEpochMillis,
        )
        if (successful) {
            sessions.recordSuccessfulCommand(
                sessionId = sessionId,
                commandLine = commandLine,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
            )
        }
    }

    /**
     * Changes whether the fixed learning destination may be read and written.
     *
     * @param enabled `true` to persist sanitized learning across application restarts.
     */
    fun setPersistenceEnabled(enabled: Boolean) {
        learning.setPersistenceEnabled(enabled)
    }

    /**
     * Removes completion state for a closed terminal session.
     *
     * @param sessionId workspace tab/session id to remove.
     */
    fun removeSession(sessionId: String) {
        sessions.removeSession(sessionId)
    }

    /** Clears every session MRU and waits for the final dirty persistence write. */
    suspend fun closeAndFlush() {
        sessions.close()
        learning.closeAndFlush()
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
    }
}
