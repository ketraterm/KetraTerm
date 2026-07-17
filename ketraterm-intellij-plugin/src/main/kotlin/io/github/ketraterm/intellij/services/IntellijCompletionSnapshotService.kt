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

import io.github.ketraterm.completion.host.TerminalCompletionSnapshotService
import io.github.ketraterm.completion.host.TerminalValueSnapshotProvider

/**
 * Application-scoped owner of bounded IntelliJ completion snapshot work.
 *
 * Two IO workers consume a bounded, non-blocking queue shared by all terminal
 * completion sessions. Submission rejects work when the queue is full instead
 * of blocking the Swing event-dispatch thread. Provider failures are isolated
 * so one failed load cannot terminate a worker. Closing this owner cancels
 * queued and active work and is safe to repeat.
 */
internal class IntellijCompletionSnapshotService : AutoCloseable {
    private val delegate = TerminalCompletionSnapshotService(coroutineName = "intellij-completion-snapshots")

    /**
     * Creates a session-owned asynchronous directory snapshot provider.
     *
     * @param onSnapshotChanged callback invoked on a snapshot worker after a
     * new active snapshot is published; the callback must arrange any required
     * Swing-thread handoff.
     * @param scanner blocking directory scanner executed only by snapshot workers.
     * @return provider that must be closed with its terminal session.
     */
    fun createDirectoryProvider(
        onSnapshotChanged: () -> Unit,
        scanner: IntellijDirectoryScanner = BoundedIntellijDirectoryScanner(),
    ): IntellijAsyncFileSystemProvider =
        delegate.createDirectoryProvider(
            onSnapshotChanged = onSnapshotChanged,
            scanner = scanner,
        )

    /**
     * Creates a session-owned asynchronous keyed-value snapshot provider.
     *
     * @param keyProvider thread-safe supplier for the current provider key.
     * @param loader blocking bounded loader executed only by snapshot workers.
     * @param onSnapshotChanged callback invoked on a snapshot worker after a
     * new active snapshot is published; the callback must arrange any required
     * Swing-thread handoff.
     * @return provider that must be closed with its terminal session.
     */
    fun <K, V> createValueProvider(
        keyProvider: () -> K,
        loader: (K) -> List<V>,
        onSnapshotChanged: () -> Unit,
    ): TerminalValueSnapshotProvider<K, V> =
        delegate.createValueProvider(
            keyProvider = keyProvider,
            loader = loader,
            onSnapshotChanged = onSnapshotChanged,
        )

    /** Cancels shared snapshot work and releases worker resources idempotently. */
    override fun close() {
        delegate.close()
    }
}
