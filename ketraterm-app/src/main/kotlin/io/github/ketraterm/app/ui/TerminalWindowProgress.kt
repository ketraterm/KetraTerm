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

import io.github.ketraterm.app.appMessages
import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import java.awt.Taskbar
import javax.swing.JFrame

/** Formats on presentation changes; tab painting consumes the cached display title. */
internal fun terminalProgressLabel(progress: TerminalProgress): String? =
    when (progress.state) {
        TerminalProgressState.HIDDEN -> null
        TerminalProgressState.NORMAL -> appMessages.text("progress.normal", progress.percentage)
        TerminalProgressState.ERROR -> appMessages.text("progress.error", progress.percentage)
        TerminalProgressState.INDETERMINATE -> appMessages.text("progress.indeterminate")
        TerminalProgressState.WARNING -> appMessages.text("progress.warning", progress.percentage)
    }

/** Native presentation is window-scoped; unsupported desktops keep the tab/title indicator. */
internal fun publishTaskbarProgress(
    frame: JFrame,
    progress: TerminalProgress,
) {
    if (!frame.isDisplayable) return
    try {
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        if (!taskbar.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW) ||
            !taskbar.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)
        ) {
            return
        }
        val state =
            when (progress.state) {
                TerminalProgressState.HIDDEN -> Taskbar.State.OFF
                TerminalProgressState.NORMAL -> Taskbar.State.NORMAL
                TerminalProgressState.ERROR -> Taskbar.State.ERROR
                TerminalProgressState.INDETERMINATE -> Taskbar.State.INDETERMINATE
                TerminalProgressState.WARNING -> Taskbar.State.PAUSED
            }
        // State must precede the value: leaving INDETERMINATE can reset the native value.
        taskbar.setWindowProgressState(frame, state)
        if (state != Taskbar.State.OFF && state != Taskbar.State.INDETERMINATE) {
            taskbar.setWindowProgressValue(frame, progress.percentage)
        }
    } catch (_: UnsupportedOperationException) {
        // Optional OS integration can disappear while the window is alive.
    } catch (_: SecurityException) {
        // A restricted desktop still retains the in-window progress indicator.
    }
}
