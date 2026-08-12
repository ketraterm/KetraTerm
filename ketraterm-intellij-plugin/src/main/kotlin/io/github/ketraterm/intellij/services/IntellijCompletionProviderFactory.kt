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
 */
internal data class IntellijCompletionProviderContext(
    val commandSpecs: List<TerminalCommandSpec>,
    val workingDirectoryUriProvider: () -> String?,
)

/**
 * One dynamically composed source.
 *
 * @property sourceEntry source and host-selected priority.
 */
internal data class IntellijCompletionProviderRegistration(
    val sourceEntry: TerminalCompletionSourceEntry,
)

/**
 * Creates a registration whose provider loads values when completion runs.
 */
internal fun <V> IntellijCompletionProviderContext.createSuspendingRegistration(
    priority: Int,
    loader: suspend (String?) -> List<V>,
    sourceFactory: (valuesProvider: suspend () -> List<V>) -> TerminalCompletionSource,
): IntellijCompletionProviderRegistration =
    IntellijCompletionProviderRegistration(
        TerminalCompletionSourceEntry(
            sourceFactory { loader(workingDirectoryUriProvider()) },
            priority,
        ),
    )
