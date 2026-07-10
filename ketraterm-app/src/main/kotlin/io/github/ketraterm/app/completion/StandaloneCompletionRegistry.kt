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
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Standalone completion wiring for one application window.
 *
 * The registry owns host-specific source composition. It creates one
 * [StandaloneCompletionSuggestionProvider] per terminal session, pairs that
 * provider with a session-scoped MRU source, and merges the MRU source ahead of
 * shared static specs. It deliberately lives in `ketraterm-app`; plugin
 * integration should build its own host registry over the shared
 * `ketraterm-completion` sources.
 *
 * @param specs static command specs shared by providers created from this registry.
 * @param persistentStatsSource optional cross-session indexed stats source
 * loaded and maintained by the standalone host.
 * @param sessionMruCapacity maximum distinct commands retained per terminal session.
 */
internal class StandaloneCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val persistentStatsSource: TerminalCommandStatsCompletionSource? = null,
    private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
) {
    init {
        require(sessionMruCapacity > 0) { "sessionMruCapacity must be > 0, was $sessionMruCapacity" }
    }

    /** Immutable command specs shared by standalone completion integration. */
    val commandSpecs = specs.toList()
    private val lock = Any()
    private val shapeStatsProvider = persistentStatsSource?.let { source -> { source.shapeSnapshot() } }
    private val specSource =
        TerminalCompletionSources
            .fromSpecs(
                specs = commandSpecs,
                shapeStatsProvider = shapeStatsProvider,
            ).let(::feedbackAware)
    private val sessionMruSources = HashMap<String, TerminalSessionMruCompletionSource>()

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
     */
    fun createProvider(
        sessionId: String,
        profileId: String? = null,
        shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
        workingDirectoryUriProvider: () -> String? = { null },
    ): StandaloneCompletionSuggestionProvider {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val mruSource = TerminalCompletionSources.sessionMru(sessionMruCapacity)
        synchronized(lock) {
            sessionMruSources[sessionId] = mruSource
        }
        val rankedMruSource = feedbackAware(mruSource)
        val sources =
            ArrayList<TerminalCompletionSourceEntry>(COMPOSED_SOURCE_CAPACITY).apply {
                add(TerminalCompletionSourceEntry(rankedMruSource, priority = SESSION_MRU_PRIORITY))
                persistentStatsSource?.let { source ->
                    add(TerminalCompletionSourceEntry(feedbackAware(source), priority = PERSISTENT_STATS_PRIORITY))
                }
                add(TerminalCompletionSourceEntry(specSource, priority = SPEC_PRIORITY))
                add(
                    TerminalCompletionSourceEntry(
                        TerminalCompletionSources.path(
                            fileSystemProvider = StandaloneFileSystemProvider,
                            commandSpecs = commandSpecs,
                        ),
                        priority = PATH_PRIORITY,
                    ),
                )
            }
        return StandaloneCompletionSuggestionProvider(
            engine =
                TerminalCompletionEngines.fromSources(
                    sources = sources,
                    commandSpecs = commandSpecs,
                ),
            contextProvider = {
                StandaloneCompletionSuggestionContext(
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUriProvider(),
                    shellCapabilities = shellCapabilities,
                )
            },
        )
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
        val source =
            synchronized(lock) {
                sessionMruSources[sessionId]
            } ?: return
        source.recordSuccessfulCommand(
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
        val source =
            synchronized(lock) {
                sessionMruSources.remove(sessionId)
            }
        source?.clear()
    }

    private fun feedbackAware(source: TerminalCompletionSource): TerminalCompletionSource {
        val statsSource = persistentStatsSource ?: return source
        return TerminalCompletionSources.feedbackAware(source) {
            statsSource.feedbackSnapshot()
        }
    }

    private object StandaloneFileSystemProvider : TerminalFileSystemProvider {
        override fun listDirectory(directoryUri: String): List<TerminalFileEntry> {
            return try {
                val uri = java.net.URI(directoryUri)
                val path =
                    java.nio.file.Paths
                        .get(uri)
                if (!java.nio.file.Files
                        .isDirectory(path)
                ) {
                    return emptyList()
                }

                java.nio.file.Files.newDirectoryStream(path).use { stream ->
                    stream.map { child ->
                        TerminalFileEntry(
                            name = child.fileName.toString(),
                            isDirectory =
                                java.nio.file.Files
                                    .isDirectory(child),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
        private const val COMPOSED_SOURCE_CAPACITY = 4
        private const val PATH_PRIORITY = 125
        private const val SESSION_MRU_PRIORITY = 100
        private const val PERSISTENT_STATS_PRIORITY = 50
        private const val SPEC_PRIORITY = 0
    }
}
