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

import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** Tests alert admission on the EDT boundary without native focus or notification windows. */
class IntellijCommandFinishedAlertsTest {
    @Test
    fun `focused pane and its descendants suppress alerts while another pane qualifies`() =
        onEdt {
            val pane = JPanel()
            val child = JTextField()
            pane.add(child)
            val notifications = mutableListOf<String>()
            for (focus in listOf(pane, child)) {
                showCommandFinishedAlert(metadata(), 10_000L, enabledSettings(), pane, focus, false) { _, body, _ ->
                    notifications += body
                }
            }
            assertTrue(notifications.isEmpty())
            showCommandFinishedAlert(metadata(), 10_000L, enabledSettings(), pane, JPanel(), false) { _, body, _ ->
                notifications += body
            }
            assertEquals(1, notifications.size)
        }

    @Test
    fun `defaults settings changes missing panes and closed sessions suppress delivery`() =
        onEdt {
            val pane = JPanel()
            var notifications = 0
            val publish: (String, String, NotificationLevel) -> Unit = { _, _, _ -> notifications++ }
            showCommandFinishedAlert(metadata(), 10_000L, KetraTermIntellijSettings.State(), pane, null, false, publish = publish)
            val disabledSettings = enabledSettings().copy(commandFinishedAlertsEnabled = false)
            showCommandFinishedAlert(metadata(), 10_000L, disabledSettings, pane, null, false, publish = publish)
            showCommandFinishedAlert(metadata(), 10_000L, enabledSettings(), null, null, false, publish = publish)
            showCommandFinishedAlert(metadata(), 10_000L, enabledSettings(), pane, null, true, publish = publish)
            showCommandFinishedAlert(metadata(), 9_999L, enabledSettings(), pane, null, false, publish = publish)
            showCommandFinishedAlert(metadata(), null, enabledSettings(), pane, null, false, publish = publish)
            showCommandFinishedAlert(
                metadata = metadata(),
                durationMillis = 10_000L,
                settings = enabledSettings(),
                pane = pane,
                focusOwner = null,
                sessionClosed = false,
                notificationsAllowed = false,
                publish = publish,
            )
            assertEquals(0, notifications)
            showCommandFinishedAlert(metadata(), 10_000L, enabledSettings(), pane, null, false, publish = publish)
            assertEquals(1, notifications)
        }

    @Test
    fun `alerts work with suggestions disabled and never expose command payloads`() =
        onEdt {
            val notifications = mutableListOf<Triple<String, String, NotificationLevel>>()
            for ((lifecycle, exitCode) in listOf(
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED to 0,
                TerminalShellIntegrationCommandLifecycle.FAILED to 7,
                TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN to null,
            )) {
                showCommandFinishedAlert(
                    metadata(lifecycle, exitCode),
                    10_000L,
                    enabledSettings().copy(smartSuggestionsEnabled = false),
                    JPanel(),
                    null,
                    false,
                ) { title, body, level -> notifications += Triple(title, body, level) }
            }
            assertEquals(3, notifications.size)
            assertEquals(
                listOf(NotificationLevel.INFO, NotificationLevel.WARNING, NotificationLevel.INFO),
                notifications.map { it.third },
            )
            for ((title, body) in notifications) {
                assertFalse(title.contains("secret"))
                assertFalse(body.contains("secret"))
                assertFalse(body.contains("<script>"))
            }
            assertEquals(KetraTermBundle.message("terminal.commandFinished.failure", 7), notifications[1].second)
        }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait { action() }

    private fun enabledSettings() = KetraTermIntellijSettings.State(commandFinishedAlertsEnabled = true)

    private fun metadata(
        lifecycle: Int = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
        exitCode: Int? = 0,
    ) = TerminalShellIntegrationCommandMetadata(
        recordId = 1,
        lifecycle = lifecycle,
        commandText = "<script>secret</script>",
        workingDirectoryUri = "file:///secret",
        exitCode = exitCode,
        startedAtEpochMillis = 0L,
        finishedAtEpochMillis = 10_000L,
    )
}
