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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Launches serialized completion-learning operations in a host-owned lifecycle scope.
 *
 * This class contains no product policy beyond the shared command-persistence
 * privacy check. The host still owns [coroutineScope], the persistence path,
 * enablement settings, and failure reporting. Cancelling the supplied scope
 * cancels queued repository operations through structured concurrency.
 *
 * @param repository suspending learning and persistence repository.
 * @param coroutineScope caller-owned lifecycle scope for infrequent learning operations.
 */
class TerminalCompletionLearningCoordinator(
    private val repository: TerminalCompletionLearningRepository,
    private val coroutineScope: CoroutineScope,
) {
    /** Mutable learning store shared with completion ranking and history sources. */
    val learningStore: TerminalCompletionLearningStore
        get() = repository.learningStore

    init {
        coroutineScope.launch { repository.initialize() }
    }

    /**
     * Launches one serialized learning mutation.
     *
     * @param mutation synchronous bounded mutation run under the repository mutex.
     */
    fun submit(mutation: () -> Unit) {
        coroutineScope.launch { repository.mutate { mutation() } }
    }

    /**
     * Records one completed command when the shared persistence privacy policy permits it.
     *
     * @param commandLine command text reported by shell integration.
     * @param successful whether the command completed successfully.
     * @param profileId optional host profile identifier.
     * @param workingDirectoryUri optional working-directory URI captured for the command.
     * @param usedAtEpochMillis non-negative completion timestamp.
     */
    fun recordCommandResult(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        if (!TerminalCompletionPersistencePolicy.allowsCommand(commandLine)) return
        submit {
            learningStore.recordCommandResult(
                commandLine = commandLine,
                successful = successful,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
                usedAtEpochMillis = usedAtEpochMillis,
            )
        }
    }

    /**
     * Launches a change to the active persistence destination.
     *
     * @param path replacement snapshot path, or `null` for memory-only learning.
     */
    fun setPersistencePath(path: Path?) {
        coroutineScope.launch { repository.setPersistencePath(path) }
    }

    /**
     * Launches a change to persistence enablement for the configured path.
     *
     * @param enabled whether the configured snapshot path may be read and written.
     */
    fun setPersistenceEnabled(enabled: Boolean) {
        coroutineScope.launch { repository.setPersistenceEnabled(enabled) }
    }
}
