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

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.ketraterm.protocol.NotificationLevel

/**
 * IntelliJ-native notification bridge for terminal OSC notification requests.
 *
 * Terminal payloads are untrusted host input. IntelliJ notification content may
 * render lightweight HTML, so this bridge escapes terminal-provided title and
 * body text before publishing through the IDE notification subsystem.
 */
internal object JvTermIntellijNotifier {
    /**
     * Notification group registered in `plugin.xml`.
     */
    const val GROUP_ID: String = "JvTerm Terminal"

    private const val DEFAULT_TITLE = "JvTerm"

    /**
     * Shows an IDE-native notification for a terminal request.
     *
     * @param project IntelliJ project that owns the terminal tab.
     * @param title terminal-provided notification title.
     * @param body terminal-provided notification body.
     * @param level terminal-provided notification severity.
     */
    fun showNotification(
        project: Project,
        title: String,
        body: String,
        level: NotificationLevel,
    ) {
        Notification(
            GROUP_ID,
            displayTitle(title),
            escapeNotificationText(body),
            notificationType(level),
        ).notify(project)
    }

    internal fun displayTitle(title: String): String =
        escapeNotificationText(title.trim().ifBlank { DEFAULT_TITLE })

    internal fun notificationType(level: NotificationLevel): NotificationType =
        when (level) {
            NotificationLevel.INFO,
            NotificationLevel.NONE,
            -> NotificationType.INFORMATION
            NotificationLevel.WARNING -> NotificationType.WARNING
            NotificationLevel.ERROR -> NotificationType.ERROR
        }

    internal fun escapeNotificationText(text: String): String {
        var firstEscapedIndex = -1
        for (index in text.indices) {
            if (requiresEscaping(text[index])) {
                firstEscapedIndex = index
                break
            }
        }
        if (firstEscapedIndex < 0) return text

        val escaped = StringBuilder(text.length + ESCAPE_BUFFER_SLACK)
        escaped.append(text, 0, firstEscapedIndex)
        for (index in firstEscapedIndex until text.length) {
            when (text[index]) {
                '<' -> escaped.append("&lt;")
                '>' -> escaped.append("&gt;")
                '&' -> escaped.append("&amp;")
                '"' -> escaped.append("&quot;")
                '\'' -> escaped.append("&#39;")
                else -> escaped.append(text[index])
            }
        }
        return escaped.toString()
    }

    private fun requiresEscaping(character: Char): Boolean =
        character == '<' ||
            character == '>' ||
            character == '&' ||
            character == '"' ||
            character == '\''

    private const val ESCAPE_BUFFER_SLACK = 16
}
