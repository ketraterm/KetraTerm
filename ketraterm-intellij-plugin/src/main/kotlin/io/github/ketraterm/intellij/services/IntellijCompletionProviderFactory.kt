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

import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/** Factory for one optional IntelliJ completion source and its lifecycle. */
internal fun interface IntellijCompletionProviderFactory {
    /**
     * Creates a source for [context].
     *
     * @return registration, or `null` when the provider is unavailable.
     */
    fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration?
}

/**
 * Stable session services available to IntelliJ completion provider factories.
 *
 * @property commandSpecs immutable specs used by the owning engine.
 * @property workingDirectoryUriProvider supplier for current session directory.
 * @property snapshotService bounded asynchronous snapshot owner.
 * @property onSnapshotChanged source-publication notification callback.
 */
internal data class IntellijCompletionProviderContext(
    val commandSpecs: List<TerminalCommandSpec>,
    val workingDirectoryUriProvider: () -> String?,
    val snapshotService: IntellijCompletionSnapshotService,
    val onSnapshotChanged: () -> Unit,
)

/**
 * One dynamically composed source plus resources closed with its session.
 *
 * @property sourceEntry source and host-selected priority.
 * @property resources closeable provider snapshots owned by the registration.
 */
internal data class IntellijCompletionProviderRegistration(
    val sourceEntry: TerminalCompletionSourceEntry,
    val resources: List<AutoCloseable> = emptyList(),
)

/** Adds local Git branch values without coupling the registry to Git4Idea. */
internal class IntellijGitBranchProviderFactory(
    private val loader: (String?) -> List<TerminalCompletionDomainValue>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration {
        val snapshotProvider =
            context.snapshotService.createValueProvider(
                keyProvider = context.workingDirectoryUriProvider,
                loader = loader,
                onSnapshotChanged = context.onSnapshotChanged,
            )
        val source =
            TerminalCompletionSources.valueDomain(
                domain = TerminalCompletionValueDomain.GIT_BRANCH,
                sourceId = SOURCE_ID,
                valuesProvider = snapshotProvider::values,
                commandSpecs = context.commandSpecs,
            )
        return IntellijCompletionProviderRegistration(
            sourceEntry =
                TerminalCompletionSourceEntry(
                    source = source,
                    priority = PRIORITY,
                ),
            resources = listOf(snapshotProvider),
        )
    }

    private companion object {
        private const val PRIORITY = 150
        private const val SOURCE_ID = "intellij-git-branch"
    }
}
