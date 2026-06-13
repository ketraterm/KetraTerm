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
package io.github.jvterm.app.ui

import com.formdev.flatlaf.extras.FlatSVGIcon
import java.awt.*
import java.io.InputStream
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * A clean, professional about dialog showcasing the JvTerm Terminal brand,
 * version information, and developer credits.
 */
internal class AboutDialog(
    parent: JFrame,
) : JDialog(parent, "About JvTerm Terminal", true) {
    init {
        contentPane = buildAboutPanel()
        defaultCloseOperation = DISPOSE_ON_CLOSE
        setSize(460, 280)
        setLocationRelativeTo(parent)
        isResizable = false
    }

    private fun buildAboutPanel(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Chrome.surface
            isOpaque = true
            border = EmptyBorder(24, 24, 24, 24)

            // Header Section: Logo + Title + Subtitle
            add(buildHeaderSection())
            add(Box.createVerticalStrut(16))

            // Divider
            add(buildDivider())
            add(Box.createVerticalStrut(16))

            // Metadata: Version + Developer
            add(buildMetadataSection())
            add(Box.createVerticalStrut(24))

            // Action: OK Button
            val okButton =
                JButton("OK").apply {
                    addActionListener {
                        dispose()
                    }
                }
            this@AboutDialog.rootPane.defaultButton = okButton
            add(buildButtonSection(okButton))
        }

    private fun buildHeaderSection(): JPanel {
        val panel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                background = Chrome.surface
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 50)
                alignmentX = LEFT_ALIGNMENT
            }

        // SVG Logo
        val logoIcon = FlatSVGIcon("io/github/jvterm/app/icons/logo.svg", 48, 48)
        val logoLabel =
            JLabel(logoIcon).apply {
                alignmentY = CENTER_ALIGNMENT
            }
        panel.add(logoLabel)
        panel.add(Box.createHorizontalStrut(16))

        // Title and Subtitle
        val titlePanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = Chrome.surface
                isOpaque = false
                alignmentY = CENTER_ALIGNMENT
            }

        val titleLabel =
            JLabel("JvTerm Terminal").apply {
                font = Font("Dialog", Font.BOLD, 20)
                foreground = Chrome.textPrimary
            }

        val subtitleLabel =
            JLabel("A high-performance, modern terminal emulator.").apply {
                font = Font("Dialog", Font.PLAIN, 11)
                foreground = Chrome.textSecondary
            }

        titlePanel.add(titleLabel)
        titlePanel.add(Box.createVerticalStrut(2))
        titlePanel.add(subtitleLabel)

        panel.add(titlePanel)
        panel.add(Box.createHorizontalGlue())

        return panel
    }

    private fun buildMetadataSection(): JPanel {
        val panel =
            JPanel().apply {
                layout = GridLayout(3, 1, 0, 4)
                background = Chrome.surface
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }

        val version = getAppVersion()
        val versionLabel =
            JLabel("Version: $version").apply {
                font = Font("Dialog", Font.PLAIN, 11)
                foreground = Chrome.textSecondary
            }

        val developerLabel =
            JLabel("Developer: Gagik Sargsyan").apply {
                font = Font("Dialog", Font.PLAIN, 11)
                foreground = Chrome.textSecondary
            }

        val licenseLabel =
            JLabel("Licensed under the Apache License, Version 2.0").apply {
                font = Font("Dialog", Font.PLAIN, 10)
                foreground = Chrome.controlTextDisabled
            }

        panel.add(versionLabel)
        panel.add(developerLabel)
        panel.add(licenseLabel)

        return panel
    }

    private fun buildDivider(): JPanel =
        JPanel().apply {
            layout = BorderLayout()
            background = Chrome.surface
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            preferredSize = Dimension(Int.MAX_VALUE, 1)

            val divider =
                object : JPanel() {
                    override fun paintComponent(g: Graphics) {
                        super.paintComponent(g)
                        val g2d = g as Graphics2D
                        g2d.color = Chrome.border
                        g2d.drawLine(0, height / 2, width, height / 2)
                    }
                }.apply {
                    background = Color(0, 0, 0, 0)
                    isOpaque = false
                }
            add(divider, BorderLayout.CENTER)
        }

    private fun buildButtonSection(okButton: JButton): JPanel {
        val panel =
            JPanel().apply {
                layout = FlowLayout(FlowLayout.RIGHT, 0, 0)
                background = Chrome.surface
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 32)
                alignmentX = LEFT_ALIGNMENT
            }

        panel.add(okButton)
        return panel
    }

    private fun getAppVersion(): String =
        try {
            val properties = Properties()
            val inputStream: InputStream? =
                AboutDialog::class.java.classLoader
                    .getResourceAsStream("io/github/jvterm/app/version.properties")
            if (inputStream != null) {
                properties.load(inputStream)
                properties.getProperty("version") ?: "1.0.0"
            } else {
                "1.0.0"
            }
        } catch (e: Exception) {
            "1.0.0"
        }
}
