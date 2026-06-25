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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession

/**
 * Narrow Swing component adapter used by command interaction logic.
 */
internal interface TerminalCommandInteractionHost {
    val session: TerminalSession?
    val renderCache: TerminalRenderCache
    val searchCache: TerminalRenderCache

    fun cellAt(
        x: Int,
        y: Int,
        cache: TerminalRenderCache,
    ): Long

    fun visibleGridRows(): Int

    fun commandNavigationAnchorRow(): Int

    fun refreshRenderCacheFromSession(session: TerminalSession)

    fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean

    fun selectAbsoluteRows(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        columns: Int,
    )

    fun scrollViewportTo(
        offsetRows: Int,
        historySize: Int,
        boundSession: TerminalSession,
    ): Boolean

    fun repaint()
}
