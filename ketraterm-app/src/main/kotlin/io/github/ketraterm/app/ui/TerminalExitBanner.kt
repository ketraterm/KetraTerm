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
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Product-owned status beside retained output; it never writes text into the terminal grid. */
internal class TerminalExitBanner(
    onClose: () -> Unit,
) : JPanel(BorderLayout(8, 0)) {
    private val message =
        JTextArea(2, 0).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("Label.font")
            minimumSize = Dimension(0, 0)
        }

    init {
        isVisible = false
        add(message, BorderLayout.CENTER)
        add(JButton(appMessages.text("session.exit.close")).apply { addActionListener { onClose() } }, BorderLayout.EAST)
        refreshColors()
    }

    fun showExitStatus(
        exitCode: Int?,
        failure: Throwable?,
    ) {
        check(SwingUtilities.isEventDispatchThread())
        message.text =
            when {
                failure != null -> {
                    val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
                    val safeDetail =
                        buildString {
                            var offset = 0
                            var shown = 0
                            while (offset < detail.length && shown < 200) {
                                val codepoint = detail.codePointAt(offset)
                                appendCodePoint(if (Character.isISOControl(codepoint)) ' '.code else codepoint)
                                offset += Character.charCount(codepoint)
                                shown++
                            }
                            if (offset < detail.length) append('…')
                        }
                    if (exitCode == null) {
                        appMessages.text("session.exit.error", safeDetail)
                    } else {
                        appMessages.text("session.exit.errorWithStatus", exitCode, safeDetail)
                    }
                }
                exitCode != null -> appMessages.text("session.exit.failed", exitCode)
                else -> appMessages.text("session.exit.unknown")
            }
        message.caretPosition = 0
        isVisible = true
        revalidate()
        repaint()
    }

    fun refreshColors() {
        message.font = UIManager.getFont("Label.font")
        background = Chrome.controlBackground
        message.background = background
        message.foreground = Chrome.textPrimary
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Chrome.border),
                BorderFactory.createEmptyBorder(8, 10, 8, 8),
            )
    }
}
