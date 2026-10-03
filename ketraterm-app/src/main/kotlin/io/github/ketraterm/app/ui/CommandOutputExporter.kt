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
package io.github.ketraterm.app.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path

/** Window-owned exports. Closing cancels work and suppresses late UI callbacks. */
internal class CommandOutputExporter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val write: (Path, String) -> Unit = { path, text -> Files.writeString(path, text) },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** Call on the EDT; failures are delivered there while this owner remains open. */
    fun export(
        path: Path,
        text: String,
        onFailure: (Exception) -> Unit,
    ): Job =
        scope.launch {
            val failure =
                runInterruptible(ioDispatcher) {
                    try {
                        write(path, text)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failure
                    }
                }
            ensureActive()
            if (failure != null) onFailure(failure)
        }

    override fun close() {
        scope.cancel()
    }
}
