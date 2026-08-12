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

import com.intellij.openapi.diagnostic.Logger
import io.github.ketraterm.completion.host.*
import kotlinx.coroutines.CoroutineScope

/**
 * Application-scoped owner of bounded IntelliJ completion snapshot work.
 *
 * Completion loads are children of IntelliJ's injected application-service
 * scope and share a suspending concurrency limit across terminal sessions.
 * Provider failures are isolated so one failed load cannot terminate sibling
 * work. Closing this owner cancels active and permit-waiting work.
 *
 * @param parentScope IntelliJ's application-service scope, or `null` for
 * isolated tests that let the delegate own its lifecycle.
 */
internal class IntellijCompletionSnapshotService(
    parentScope: CoroutineScope? = null,
) : AutoCloseable {
    private val delegate =
        TerminalCompletionSnapshotService(
            parentScope = parentScope,
            coroutineName = "intellij-completion-snapshots",
            onBackgroundFailure = { failure ->
                LOG.warn("IntelliJ completion snapshot work failed", failure)
            },
        )

    /**
     * Creates a session-owned asynchronous directory snapshot provider.
     *
     * @param onSnapshotChanged callback invoked on a snapshot worker after a
     * new active snapshot is published; the callback must arrange any required
     * Swing-thread handoff.
     * @param scanner suspending directory scanner.
     * @return provider that must be closed with its terminal session.
     */
    fun createDirectoryProvider(
        onSnapshotChanged: () -> Unit,
        scanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
    ): TerminalAsyncFileSystemProvider =
        delegate.createDirectoryProvider(
            onSnapshotChanged = onSnapshotChanged,
            scanner = scanner,
        )

    /**
     * Creates a session-owned asynchronous keyed-value snapshot provider.
     *
     * @param loader suspending bounded loader whose request is cancelled when
     * its key is superseded or provider closes.
     * @param onSnapshotChanged callback invoked on a snapshot worker after a
     * new active snapshot is published; the callback must arrange any required
     * Swing-thread handoff.
     * @return provider that must be closed with its terminal session.
     */
    fun <K, V> createValueProvider(
        loader: suspend (K) -> List<V>,
        onSnapshotChanged: () -> Unit,
    ): TerminalValueSnapshotProvider<K, V> =
        delegate.createValueProvider(
            loader = loader,
            onSnapshotChanged = onSnapshotChanged,
        )

    /** Cancels shared snapshot work and releases worker resources idempotently. */
    override fun close() {
        delegate.close()
    }

    private companion object {
        private val LOG = Logger.getInstance(IntellijCompletionSnapshotService::class.java)
    }
}
