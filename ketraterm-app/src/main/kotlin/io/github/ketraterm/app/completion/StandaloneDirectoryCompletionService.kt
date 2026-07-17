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

import io.github.ketraterm.completion.host.*

/** Window-scoped owner of shared bounded completion snapshot workers. */
internal class StandaloneDirectoryCompletionService : AutoCloseable {
    private val delegate = TerminalCompletionSnapshotService(coroutineName = "directory-completion")

    /** Creates a session-owned asynchronous filesystem provider. */
    fun createProvider(onSnapshotChanged: () -> Unit): StandaloneAsyncFileSystemProvider =
        delegate.createDirectoryProvider(onSnapshotChanged)

    /** Cancels queued and active directory snapshot work. */
    override fun close() = delegate.close()
}

/** Standalone-local name for the shared bounded completion scheduler. */
internal typealias StandaloneDirectoryLoadScheduler = TerminalCompletionLoadScheduler

/** Standalone-local name for the shared generation-safe filesystem provider. */
internal typealias StandaloneAsyncFileSystemProvider = TerminalAsyncFileSystemProvider

/** Standalone-local name for the shared local path resolver. */
internal typealias StandalonePathResolver = TerminalCompletionPathResolver

/** Standalone-local name for the shared blocking directory scan contract. */
internal typealias StandaloneDirectoryScanner = TerminalDirectoryScanner

/** Standalone-local name for the shared bounded local directory scanner. */
internal typealias BoundedStandaloneDirectoryScanner = TerminalBoundedDirectoryScanner
