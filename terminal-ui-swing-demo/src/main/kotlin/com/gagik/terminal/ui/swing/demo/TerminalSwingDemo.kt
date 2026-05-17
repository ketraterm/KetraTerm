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
package com.gagik.terminal.ui.swing.demo

import com.gagik.terminal.pty.TerminalPtyEventListener
import com.gagik.terminal.pty.TerminalPtyOptions
import com.gagik.terminal.pty.TerminalPtySessions
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalTheme
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Starts a standalone Swing terminal demo backed by a local PTY.
 */
fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        TerminalSwingDemo.start(DemoShellCommand.resolve(args.toList()))
    }
}

private object TerminalSwingDemo {
    private const val INITIAL_COLUMNS = 100
    private const val INITIAL_ROWS = 30

    fun start(command: List<String>) {
        var currentTheme = TerminalTheme.CAMPBELL
        var currentSettings =
            TerminalSwingSettings(
                columns = INITIAL_COLUMNS,
                rows = INITIAL_ROWS,
                theme = currentTheme,
            )

        val terminal = TerminalSwingTerminal { currentSettings }

        val frame = JFrame("Terminal Swing Demo")

        // Add a premium JMenuBar for dynamic theme switching
        val menuBar = javax.swing.JMenuBar()
        val themeMenu = javax.swing.JMenu("Themes")

        TerminalTheme.entries.forEach { theme ->
            val themeDisplayName =
                theme.name.lowercase().split("_").joinToString(" ") {
                    it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                }
            val menuItem = javax.swing.JMenuItem(themeDisplayName)
            menuItem.addActionListener {
                currentTheme = theme
                currentSettings =
                    currentSettings.copy(
                        theme = currentTheme,
                        palette = currentTheme.createPalette(),
                    )
                terminal.reloadSettings()
            }
            themeMenu.add(menuItem)
        }
        menuBar.add(themeMenu)
        val widthMenu = javax.swing.JMenu("Width")
        val ambiguousWidthItem =
            javax.swing.JCheckBoxMenuItem(
                "Ambiguous as wide",
                currentSettings.treatAmbiguousAsWide,
            )
        ambiguousWidthItem.addActionListener {
            currentSettings =
                currentSettings.copy(
                    treatAmbiguousAsWide = ambiguousWidthItem.isSelected,
                )
            terminal.reloadSettings()
        }
        widthMenu.add(ambiguousWidthItem)
        menuBar.add(widthMenu)
        frame.jMenuBar = menuBar
        val listener = DemoPtyEventListener(frame)

        val session =
            try {
                TerminalPtySessions.start(
                    TerminalPtyOptions(
                        command = command,
                        columns = INITIAL_COLUMNS,
                        rows = INITIAL_ROWS,
                        treatAmbiguousAsWide = currentSettings.treatAmbiguousAsWide,
                        eventListener = listener,
                    ),
                )
            } catch (exception: Exception) {
                JOptionPane.showMessageDialog(
                    frame,
                    exception.message ?: exception.javaClass.name,
                    "Unable to start terminal",
                    JOptionPane.ERROR_MESSAGE,
                )
                return
            }

        terminal.bind(session)
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.layout = BorderLayout()
        frame.add(terminal, BorderLayout.CENTER)
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent) {
                    session.close()
                }
            },
        )
        terminal.addComponentListener(DemoResizeAdapter(terminal, session))

        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        terminal.requestFocusInWindow()
        session.notifyRenderDirty()
    }
}

private object DemoShellCommand {
    fun resolve(args: List<String>): List<String> =
        if (args.isNotEmpty()) {
            args
        } else if (isWindows()) {
            windowsPowerShellCommand()
        } else {
            TerminalPtyOptions.defaultCommand()
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private fun windowsPowerShellCommand(): List<String> {
        val systemRoot = System.getenv("SystemRoot")
        if (!systemRoot.isNullOrBlank()) {
            val powershell =
                Path.of(
                    systemRoot,
                    "System32",
                    "WindowsPowerShell",
                    "v1.0",
                    "powershell.exe",
                )
            if (Files.isRegularFile(powershell)) {
                return listOf(powershell.toString(), "-NoLogo")
            }
        }

        return listOf("powershell.exe", "-NoLogo")
    }
}

private class DemoResizeAdapter(
    private val terminal: TerminalSwingTerminal,
    private val session: TerminalSession,
) : ComponentAdapter() {
    private var columns: Int = -1
    private var rows: Int = -1

    override fun componentResized(event: ComponentEvent) {
        val size = terminal.visibleGridSize()
        if (size.width == columns && size.height == rows) return

        columns = size.width
        rows = size.height
        session.resize(columns, rows)
        session.notifyRenderDirty()
    }
}

private class DemoPtyEventListener(
    private val frame: JFrame,
) : TerminalPtyEventListener {
    override fun bell(session: TerminalSession) {
        SwingUtilities.invokeLater {
            frame.toolkit.beep()
        }
    }

    override fun iconTitleChanged(
        session: TerminalSession,
        title: String,
    ) = Unit

    override fun windowTitleChanged(
        session: TerminalSession,
        title: String,
    ) {
        SwingUtilities.invokeLater {
            frame.title =
                if (title.isBlank()) {
                    "Terminal Swing Demo"
                } else {
                    title
                }
        }
    }

    override fun listenerFailed(
        session: TerminalSession,
        exception: Exception,
    ) = Unit
}
