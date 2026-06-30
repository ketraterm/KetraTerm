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
package io.github.ketraterm.session

/**
 * Listener for terminal session lifecycle completion.
 *
 * Implementations are called after parser cleanup has run. The callback is
 * failure-isolated by [TerminalSession], so throwing from this method does not
 * prevent session cleanup or other listeners from being notified.
 */
fun interface TerminalSessionCloseListener {
    /**
     * Called once when [session] closes locally, exits remotely, or reports a
     * transport failure.
     *
     * @param session session that reached a terminal lifecycle state.
     * @param event close metadata captured from the connector/session path.
     */
    fun sessionClosed(
        session: TerminalSession,
        event: TerminalSessionCloseEvent,
    )
}
