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

import io.github.jvterm.workspace.TerminalSshProfile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class SshAuthenticationDialog(
    parent: Frame,
    private val profile: TerminalSshProfile,
) : JDialog(parent, "Connect to ${profile.displayName}", true) {
    private val passwordField = JPasswordField()
    private var request: SshOpenRequest? = null

    init {
        layout = BorderLayout()
        minimumSize = Dimension(420, 220)
        add(buildContentPanel(), BorderLayout.CENTER)
        add(buildFooterPanel(), BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(parent)
    }

    fun showDialog(): SshOpenRequest? {
        isVisible = true
        return request
    }

    private fun buildContentPanel(): JPanel =
        JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(18, 18, 12, 18)
            background = Chrome.surface

            add(
                JLabel("${profile.username}@${profile.host}:${profile.port}").apply {
                    foreground = Chrome.textPrimary
                    horizontalAlignment = SwingConstants.LEFT
                    font = font.deriveFont(15f)
                },
                constraints(row = 0, column = 0, width = 2, bottom = 14),
            )
            add(fieldLabel("Password:"), constraints(row = 1, column = 0, right = 10))
            add(
                passwordField.apply {
                    preferredSize = Dimension(240, 28)
                },
                constraints(row = 1, column = 1, weightX = 1.0, fill = GridBagConstraints.HORIZONTAL),
            )
            add(
                JLabel(SshConnectionSecurity.trustSourceText(profile)).apply {
                    foreground = Chrome.textSecondary
                },
                constraints(row = 2, column = 0, width = 2, top = 14),
            )
        }

    private fun buildFooterPanel(): JPanel {
        val connectButton = JButton("Connect")
        val cancelButton = JButton("Cancel")
        connectButton.isEnabled = false

        passwordField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent?) = updateConnectButton()

                override fun removeUpdate(event: DocumentEvent?) = updateConnectButton()

                override fun changedUpdate(event: DocumentEvent?) = updateConnectButton()

                private fun updateConnectButton() {
                    connectButton.isEnabled = passwordField.document.length > 0
                }
            },
        )

        connectButton.addActionListener {
            val authentication = SshConnectionSecurity.passwordAuthentication(passwordField.password)
            if (authentication != null) {
                request =
                    SshOpenRequest(
                        authentication = authentication,
                        hostKeyPolicy = SshConnectionSecurity.hostKeyPolicyFor(profile),
                    )
            }
            passwordField.text = ""
            dispose()
        }
        cancelButton.addActionListener {
            passwordField.text = ""
            dispose()
        }
        rootPane.defaultButton = connectButton

        return JPanel(FlowLayout(FlowLayout.RIGHT, 12, 12)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Chrome.border)
            background = Chrome.surface
            add(cancelButton)
            add(connectButton)
        }
    }

    private fun fieldLabel(text: String): JLabel =
        JLabel(text).apply {
            foreground = Chrome.textPrimary
        }

    private fun constraints(
        row: Int,
        column: Int,
        width: Int = 1,
        weightX: Double = 0.0,
        fill: Int = GridBagConstraints.NONE,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
    ): GridBagConstraints =
        GridBagConstraints().apply {
            gridy = row
            gridx = column
            gridwidth = width
            this.weightx = weightX
            this.fill = fill
            anchor = GridBagConstraints.WEST
            insets = Insets(top, 0, bottom, right)
        }
}
