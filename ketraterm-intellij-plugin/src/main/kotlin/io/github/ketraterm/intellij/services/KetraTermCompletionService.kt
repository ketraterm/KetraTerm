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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSourcePrior
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.CoroutineScope

/**
 * Application-level owner of IntelliJ completion learning and session sources.
 *
 * The service owns persistent statistics and one [IntellijCompletionRegistry].
 * IntelliJ disposal closes all session providers and cancels registry-owned work.
 */
@Service(Service.Level.APP)
internal class KetraTermCompletionService(
    coroutineScope: CoroutineScope,
) : Disposable {
    private val settings = KetraTermIntellijSettings.getInstance()
    private val learningStore = TerminalCompletionLearningStore()
    private val learningRepository =
        TerminalCompletionLearningRepository(
            learningStore = learningStore,
            initialPersistencePath =
                PathManager
                    .getSystemDir()
                    .resolve("ketraterm")
                    .resolve(TerminalCompletionLearningRepository.currentFileName()),
            persistenceEnabled = settings.completionLearningPersistenceEnabled(),
        )
    private val registry =
        IntellijCompletionRegistry(
            statsSource = learningStore,
            learningRepository = learningRepository,
            coroutineScope = coroutineScope,
        )
    private val settingsListener: () -> Unit = {
        registry.setPersistenceEnabled(settings.completionLearningPersistenceEnabled())
    }

    init {
        settings.addChangeListener(settingsListener)
    }

    /**
     * Creates completion resources bound to one terminal workspace tab.
     *
     * @param project IntelliJ project used for project-aware VFS and Git queries.
     * @param tab terminal tab providing identity, profile, and working-directory state.
     * @return session resources that the owning terminal pane must close.
     * @throws IllegalStateException if application-level completion has been disposed.
     */
    fun openSession(
        project: Project,
        tab: TerminalWorkspaceTab,
    ): IntellijCompletionSession =
        registry.openSession(
            IntellijCompletionSessionContext(
                sessionId = tab.id,
                profileId = tab.profile.id,
                workingDirectoryUriProvider = { tab.currentWorkingDirectoryUri },
                shellCapabilities = tab.profile.kind.intellijCompletionShellCapabilities(),
                additionalSources =
                    listOf(
                        TerminalCompletionSourceEntry(
                            intellijGitCompletionSource(
                                loader = IntellijGitCompletionLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GIT_REFERENCE,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGitStatusPathCompletionSource(
                                loader = IntellijGitStatusPathLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GIT_STATUS_PATH,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGradleTaskCompletionSource(
                                loader = IntellijGradleTaskLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.GRADLE_TASK,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijProjectFileCompletionSource(
                                loader = IntellijProjectFileLoader(project)::load,
                            ),
                            TerminalCompletionSourcePrior.PROJECT_FUZZY_PATH,
                        ),
                    ),
                directoryScanner = IntellijProjectDirectoryScanner(project),
            ),
        )

    /**
     * Records one shell-integration command completion for MRU and learned ranking.
     *
     * Privacy policy is applied before any command is persisted.
     *
     * @param tab terminal tab that executed the command.
     * @param metadata trusted shell-integration command lifecycle metadata.
     */
    fun recordFinishedCommand(
        tab: TerminalWorkspaceTab,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        registry.recordFinishedCommand(
            sessionId = tab.id,
            profileId = tab.profile.id,
            metadata = metadata,
        )
    }

    /** Closes session sources. */
    override fun dispose() {
        settings.removeChangeListener(settingsListener)
        registry.close()
    }

    companion object {
        /**
         * Returns the application service instance.
         *
         * @return IntelliJ-managed completion service.
         */
        fun getInstance(): KetraTermCompletionService = service()
    }
}
