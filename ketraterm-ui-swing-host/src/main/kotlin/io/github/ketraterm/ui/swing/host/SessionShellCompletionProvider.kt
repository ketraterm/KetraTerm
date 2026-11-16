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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.session.TerminalSession

/**
 * Adapts the session's native command authority to the completion engine.
 * The implementation is shared with the optional headless completion-session library.
 * This provider borrows the session and owns no jobs.
 */
@ExperimentalTerminalCompletionApi
public class SessionShellCompletionProvider(
    session: TerminalSession,
) : TerminalCompletionShellProvider {
    private val delegate =
        io.github.ketraterm.completion.session
            .SessionShellCompletionProvider(session)

    override fun commandAliases(): Map<String, String> = delegate.commandAliases()

    override suspend fun complete(
        request: TerminalCompletionRequest,
        context: TerminalCompletionContext,
        limit: Int,
    ): TerminalCompletionShellResult? = delegate.complete(request, context, limit)
}
