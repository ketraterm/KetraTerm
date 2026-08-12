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

import io.github.ketraterm.completion.host.TerminalAsyncFileSystemProvider
import io.github.ketraterm.completion.host.TerminalCompletionSnapshotService

/** Window-scoped owner of structured completion snapshot work. */
internal class StandaloneDirectoryCompletionService : AutoCloseable {
    private val logger = System.getLogger(StandaloneDirectoryCompletionService::class.java.name)
    private val delegate =
        TerminalCompletionSnapshotService(
            coroutineName = "directory-completion",
            onBackgroundFailure = { failure ->
                logger.log(System.Logger.Level.WARNING, "Standalone completion snapshot work failed", failure)
            },
        )

    /** Creates a session-owned asynchronous filesystem provider. */
    fun createProvider(onSnapshotChanged: () -> Unit): TerminalAsyncFileSystemProvider = delegate.createDirectoryProvider(onSnapshotChanged)

    /** Cancels active and permit-waiting directory snapshot work. */
    override fun close() = delegate.close()
}
