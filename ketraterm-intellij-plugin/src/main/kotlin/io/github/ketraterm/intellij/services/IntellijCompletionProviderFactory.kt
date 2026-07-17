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

import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.model.TerminalCommandSpec

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

/**
 * Creates the standard registration for one keyed asynchronous snapshot source.
 *
 * Keeping provider creation and resource ownership together prevents factories
 * from publishing a source without also closing its backing snapshot.
 */
internal fun <V> IntellijCompletionProviderContext.createSnapshotRegistration(
    priority: Int,
    loader: (String?) -> List<V>,
    sourceFactory: (valuesProvider: () -> List<V>) -> TerminalCompletionSource,
): IntellijCompletionProviderRegistration {
    val snapshotProvider =
        snapshotService.createValueProvider(
            keyProvider = workingDirectoryUriProvider,
            loader = loader,
            onSnapshotChanged = onSnapshotChanged,
        )
    return try {
        IntellijCompletionProviderRegistration(
            sourceEntry = TerminalCompletionSourceEntry(sourceFactory(snapshotProvider::values), priority),
            resources = listOf(snapshotProvider),
        )
    } catch (failure: Throwable) {
        runCatching(snapshotProvider::close).exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}
