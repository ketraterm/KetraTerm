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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSourcePrior
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Application-level owner of IntelliJ completion learning and product sources.
 *
 * The service owns one [IntellijCompletionRegistry] and an independent scope
 * that remains alive until final learning persistence has completed.
 */
@Service(Service.Level.APP)
internal class KetraTermCompletionService : Disposable {
    private val lifecycle = IntellijCompletionLifecycle()
    private val settings = KetraTermIntellijSettings.getInstance()
    private val persistencePath =
        PathManager
            .getSystemDir()
            .resolve("ketraterm")
            .resolve(TerminalCompletionLearningCoordinator.currentFileName())
    private val completionRuntime =
        createCompletionRuntime(
            persistencePath = persistencePath,
            persistenceEnabled = settings.completionLearningPersistenceEnabled(),
        )
    private val settingsListener: () -> Unit = {
        lifecycle.ifOpen {
            completionRuntime.registry.setPersistenceEnabled(settings.completionLearningPersistenceEnabled())
        }
    }

    init {
        try {
            settings.addChangeListener(settingsListener)
        } catch (failure: Throwable) {
            try {
                closeCompletionLearningWithinBudget()
            } catch (closeFailure: Throwable) {
                if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            } finally {
                completionRuntime.scope.cancel()
            }
            throw failure
        }
    }

    /**
     * Creates completion resources for one terminal workspace tab.
     *
     * @param project IntelliJ project used for project-aware VFS and Git queries.
     * @param tab terminal tab providing identity, profile, and working-directory state.
     * @return provider and feedback resources consumed by the terminal pane.
     * @throws IllegalStateException if application-level completion has been disposed.
     */
    fun createResources(
        project: Project,
        tab: TerminalWorkspaceTab,
    ): IntellijCompletionResources {
        val context =
            IntellijCompletionContext(
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
                            intellijGitCommitCompletionSource(
                                loader = IntellijGitCommitCompletionLoader(project)::load,
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
            )
        return lifecycle.requireOpen {
            completionRuntime.registry.createResources(context)
        }
    }

    /**
     * Records one shell-integration command completion for shared learning.
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
        lifecycle.ifOpen {
            completionRuntime.registry.recordFinishedCommand(
                profileId = tab.profile.id,
                metadata = metadata,
            )
        }
    }

    /** Durably flushes learned completion state before cancelling its owned scope. */
    override fun dispose() {
        if (!lifecycle.beginClose()) return
        var failure: Throwable? = null
        try {
            settings.removeChangeListener(settingsListener)
        } catch (listenerFailure: Throwable) {
            failure = listenerFailure
        }
        try {
            closeCompletionLearningWithinBudget()
        } catch (flushFailure: Throwable) {
            val firstFailure = failure
            if (firstFailure == null) {
                failure = flushFailure
            } else if (firstFailure !== flushFailure) {
                firstFailure.addSuppressed(flushFailure)
            }
        }
        completionRuntime.scope.cancel()
        failure?.let { throw it }
    }

    private fun closeCompletionLearningWithinBudget() {
        val completed =
            runBlocking {
                withTimeoutOrNull(COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS.milliseconds) {
                    completionRuntime.registry.closeAndFlush()
                    true
                } ?: false
            }
        if (!completed) {
            LOG.warn(
                "Completion learning persistence exceeded its " +
                    "$COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS ms shutdown budget; continuing disposal",
            )
        }
    }

    private class CompletionRuntime(
        val scope: CoroutineScope,
        val registry: IntellijCompletionRegistry,
    )

    companion object {
        private fun createCompletionRuntime(
            persistencePath: Path,
            persistenceEnabled: Boolean,
        ): CompletionRuntime {
            val scope =
                CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ketraterm-completion-persistence"))
            return try {
                CompletionRuntime(
                    scope = scope,
                    registry =
                        IntellijCompletionRegistry(
                            persistencePath = persistencePath,
                            persistenceEnabled = persistenceEnabled,
                            coroutineScope = scope,
                            onPersistenceLoadFailure = { failure ->
                                LOG.warn(
                                    "Completion learning persistence was disabled because existing data could not be loaded",
                                    failure,
                                )
                            },
                        ),
                )
            } catch (failure: Throwable) {
                scope.cancel()
                throw failure
            }
        }

        /**
         * Returns the application service instance.
         *
         * @return IntelliJ-managed completion service.
         */
        fun getInstance(): KetraTermCompletionService = service()

        private val LOG: Logger = Logger.getInstance(KetraTermCompletionService::class.java)
        private const val COMPLETION_PERSISTENCE_DURABILITY_BUDGET_MILLIS = 500L
    }
}

internal class IntellijCompletionLifecycle {
    private val lock = Any()
    private var closed = false

    fun <T> requireOpen(action: () -> T): T =
        synchronized(lock) {
            check(!closed) { "IntelliJ completion service is disposed" }
            action()
        }

    fun ifOpen(action: () -> Unit) {
        synchronized(lock) {
            if (!closed) action()
        }
    }

    fun beginClose(): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            true
        }
}
