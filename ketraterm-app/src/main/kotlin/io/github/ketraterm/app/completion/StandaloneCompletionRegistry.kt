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
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider

/**
 * Standalone completion wiring for one application window.
 *
 * The registry supplies standalone context and local-file access around the
 * shared [TerminalCompletionSessionRegistry].
 *
 * @param specs static command specs shared by providers created from this registry.
 * @param persistentStatsSource optional cross-session indexed statistics store
 * loaded and maintained by the standalone host.
 * @param sessionMruCapacity maximum distinct commands retained per terminal session.
 */
internal class StandaloneCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    persistentStatsSource: TerminalCompletionLearningStore? = null,
    sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
) : AutoCloseable {
    private val sessions =
        TerminalCompletionSessionRegistry(
            commandSpecs = specs,
            learningStore = persistentStatsSource,
            sessionMruCapacity = sessionMruCapacity,
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
     * Records one successful command for the owning session MRU source.
     *
     * Calls for missing sessions are ignored because command lifecycle events can
     * race with tab close on shutdown.
     *
     * @param sessionId workspace tab/session id that produced the command.
     * @param commandLine command text captured from shell integration metadata.
     * @param profileId profile id active when the command ran.
     * @param workingDirectoryUri current-working-directory URI captured at command start.
     */
    fun recordSuccessfulCommand(
        sessionId: String,
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
    ) {
        sessions.recordSuccessfulCommand(
            sessionId = sessionId,
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )
    }

    /**
     * Removes completion state for a closed terminal session.
     *
     * @param sessionId workspace tab/session id to remove.
     */
    fun removeSession(sessionId: String) {
        sessions.removeSession(sessionId)
    }

    /** Permanently closes the registry and clears every session MRU. */
    override fun close() {
        sessions.close()
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
    }
}
