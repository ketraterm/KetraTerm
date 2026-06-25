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
package io.github.ketraterm.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * Small IDE-native placeholder views shown before a terminal session is bound.
 */
internal object JvTermTerminalStartupView {
    /**
     * Creates a lightweight view displayed while a shell is starting.
     *
     * @param profileName user-visible shell profile name.
     * @return Swing component for the terminal content tab.
     */
    fun starting(profileName: String): JComponent =
        panel(
            title = "Starting $profileName...",
            detail = "Preparing local terminal session.",
        )

    /**
     * Creates a failure view displayed when the shell cannot be started.
     *
     * @param profileName user-visible shell profile name.
     * @param error startup failure.
     * @return Swing component for the terminal content tab.
     */
    fun failure(
        profileName: String,
        error: Throwable,
    ): JComponent =
        panel(
            title = "Unable to start $profileName",
            detail = error.message ?: error.javaClass.name,
        )

    private fun panel(
        title: String,
        detail: String,
    ): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(16)
            background = JBColor.PanelBackground
            add(
                JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    isOpaque = false
                    add(JBLabel(title), BorderLayout.NORTH)
                    add(
                        JBLabel(detail).apply {
                            foreground = JBColor.GRAY
                        },
                        BorderLayout.CENTER,
                    )
                },
                BorderLayout.NORTH,
            )
        }
}
