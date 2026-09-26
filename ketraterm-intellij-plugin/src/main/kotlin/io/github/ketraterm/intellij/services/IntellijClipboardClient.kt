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

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/**
 * Clipboard lifetime for one IDE client connection.
 *
 * Registered with `client="all"` at application and project level: the former
 * guards clipboard identity, the latter owns terminal bindings. Service instance
 * identity also distinguishes reconnections that reuse a [ClientId].
 */
internal class IntellijClipboardClient internal constructor(
    val coroutineScope: CoroutineScope,
    val clientId: ClientId,
    private val currentClient: () -> IntellijClipboardClient?,
) : Disposable {
    constructor(coroutineScope: CoroutineScope) : this(
        coroutineScope,
        ClientId.current,
        { ApplicationManager.getApplication().getServiceIfCreated(IntellijClipboardClient::class.java) },
    )

    constructor(project: Project, coroutineScope: CoroutineScope) : this(
        coroutineScope,
        ClientId.current,
        { project.getServiceIfCreated(IntellijClipboardClient::class.java) },
    )

    @Volatile private var disposed = false

    val isAlive: Boolean get() = !disposed && coroutineScope.isActive

    /** Rejects a missing, foreign, disposed, or replacement client before clipboard access. */
    fun checkCurrent() {
        if (!isAlive || ClientId.current != clientId || currentClient() !== this) {
            throw CancellationException("Terminal clipboard client is no longer current")
        }
    }

    override fun dispose() {
        disposed = true
    }
}
