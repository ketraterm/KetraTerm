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
package io.github.ketraterm.workspace

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** FIFO bounded by 64 ordinary requests, one cleanup per owned tab, and one final shutdown. */
internal class WorkspaceOperations(
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("terminal-workspace-operations"))
    private val admission = Any()

    // Admission below enforces the bounds. A fixed channel capacity would let ordinary work
    // consume the cleanup reservations needed when a host irreversibly disposes its views.
    private val pending = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val retiringTabs = HashSet<String>()
    private var ordinaryQueued = 0
    private var finalOperation: (suspend () -> Unit)? = null

    init {
        scope.launch {
            try {
                for (operation in pending) runOperation(operation)
                val cleanup = synchronized(admission) { finalOperation }
                if (cleanup != null) runOperation(cleanup)
            } finally {
                scope.cancel()
            }
        }
    }

    fun submit(operation: suspend () -> Unit): Boolean =
        synchronized(admission) {
            if (finalOperation != null || ordinaryQueued == CAPACITY) return false
            ordinaryQueued++
            check(
                pending
                    .trySend {
                        synchronized(admission) { ordinaryQueued-- }
                        operation()
                    }.isSuccess,
            )
            true
        }

    /** The caller supplies distinct, currently owned tab ids. Each tab can reserve cleanup once. */
    fun submitCleanup(
        ids: List<String>,
        operation: suspend () -> Unit,
    ): Boolean =
        synchronized(admission) {
            if (ids.isEmpty()) return submit(operation)
            if (finalOperation != null || ids.any(retiringTabs::contains)) return false
            retiringTabs.addAll(ids)
            check(
                pending
                    .trySend {
                        try {
                            operation()
                        } finally {
                            synchronized(admission) { for (id in ids) retiringTabs.remove(id) }
                        }
                    }.isSuccess,
            )
            true
        }

    /** Seals admission, drains accepted operations, then executes cleanup even when the queue was full. */
    fun finish(operation: suspend () -> Unit): Boolean =
        synchronized(admission) {
            if (finalOperation != null) return false
            finalOperation = operation
            pending.close()
            true
        }

    private suspend fun runOperation(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (failure: Throwable) {
            // Operations deliver their result themselves. Isolate a failing consumer callback
            // so that it cannot strand subsequent accepted work or final resource cleanup.
            System
                .getLogger(WorkspaceOperations::class.java.name)
                .log(System.Logger.Level.WARNING, "Workspace operation completion failed", failure)
        }
    }

    internal companion object {
        const val CAPACITY = 64
    }
}
