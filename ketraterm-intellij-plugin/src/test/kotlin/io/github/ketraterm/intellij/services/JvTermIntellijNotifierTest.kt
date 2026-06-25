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

import com.intellij.notification.NotificationType
import io.github.ketraterm.protocol.NotificationLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests IntelliJ notification mapping without requiring an open IDE window.
 */
class JvTermIntellijNotifierTest {
    @Test
    fun `maps terminal severities to IntelliJ notification types`() {
        assertEquals(NotificationType.INFORMATION, JvTermIntellijNotifier.notificationType(NotificationLevel.INFO))
        assertEquals(NotificationType.INFORMATION, JvTermIntellijNotifier.notificationType(NotificationLevel.NONE))
        assertEquals(NotificationType.WARNING, JvTermIntellijNotifier.notificationType(NotificationLevel.WARNING))
        assertEquals(NotificationType.ERROR, JvTermIntellijNotifier.notificationType(NotificationLevel.ERROR))
    }

    @Test
    fun `blank notification title falls back to product name`() {
        assertEquals("JvTerm", JvTermIntellijNotifier.displayTitle("  "))
    }

    @Test
    fun `terminal notification text is escaped before publishing to IDE`() {
        assertEquals(
            "&lt;b&gt;&quot;build&quot; &amp; &#39;test&#39;&lt;/b&gt;",
            JvTermIntellijNotifier.escapeNotificationText("<b>\"build\" & 'test'</b>"),
        )
    }

    @Test
    fun `plain terminal notification text is returned unchanged`() {
        val text = "build finished"

        assertEquals(text, JvTermIntellijNotifier.escapeNotificationText(text))
    }
}
