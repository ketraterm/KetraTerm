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

import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Thread-safe manager that handles showing native desktop notifications.
 *
 * It uses Java AWT's [SystemTray] and [TrayIcon] to request the host OS to display
 * notification banners. It maintains a single [TrayIcon] instance to avoid tray clutter,
 * and automatically removes it after a period of inactivity.
 *
 * All operations on AWT SystemTray and Timer are bound to the Event Dispatch Thread (EDT).
 */
internal object DesktopNotificationManager {
    private var trayIcon: TrayIcon? = null
    private var removeTimer: Timer? = null
    private const val REMOVE_DELAY_MILLIS = 10_000

    /**
     * Displays a desktop notification.
     *
     * This method can be called from any thread; it will safely schedule execution
     * on the Event Dispatch Thread.
     *
     * @param title the notification title (e.g. command name). If blank, it will show "JvTerm".
     * @param body the notification body text.
     */
    fun showNotification(
        title: String,
        body: String,
        level: io.github.ketraterm.protocol.NotificationLevel,
    ) {
        SwingUtilities.invokeLater {
            if (!SystemTray.isSupported()) {
                return@invokeLater
            }

            val systemTray =
                try {
                    SystemTray.getSystemTray()
                } catch (e: Exception) {
                    return@invokeLater
                }

            val displayTitle = title.ifBlank { "JvTerm" }

            var icon = trayIcon
            if (icon == null) {
                val image = createTrayImage()
                icon = TrayIcon(image, "JvTerm")
                icon.isImageAutoSize = true
                trayIcon = icon
            }

            // If the icon is not in the system tray, add it
            if (!systemTray.trayIcons.contains(icon)) {
                try {
                    systemTray.add(icon)
                } catch (e: Exception) {
                    return@invokeLater
                }
            }

            val messageType =
                when (level) {
                    io.github.ketraterm.protocol.NotificationLevel.INFO -> TrayIcon.MessageType.INFO
                    io.github.ketraterm.protocol.NotificationLevel.WARNING -> TrayIcon.MessageType.WARNING
                    io.github.ketraterm.protocol.NotificationLevel.ERROR -> TrayIcon.MessageType.ERROR
                    io.github.ketraterm.protocol.NotificationLevel.NONE -> TrayIcon.MessageType.NONE
                }

            // Show the OS notification
            icon.displayMessage(displayTitle, body, messageType)

            // Reset or start the removal timer
            removeTimer?.stop()
            val timer =
                Timer(REMOVE_DELAY_MILLIS) {
                    try {
                        systemTray.remove(icon)
                    } catch (e: Exception) {
                        // Ignore failures on removal
                    }
                }
            timer.isRepeats = false
            timer.start()
            removeTimer = timer
        }
    }

    private fun createTrayImage(): Image {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Rounded background
            g.color = Color(30, 144, 255) // nice terminal blue
            g.fillRoundRect(0, 0, 16, 16, 4, 4)
            // Prompt character ">"
            g.color = Color.WHITE
            g.font = Font("Monospaced", Font.BOLD, 12)
            g.drawString(">", 3, 12)
        } finally {
            g.dispose()
        }
        return image
    }
}
