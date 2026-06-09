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
package com.gagik.terminal.standalone

import com.gagik.terminal.pty.TerminalPtyEventListener
import com.gagik.terminal.pty.TerminalPtyOptions
import com.gagik.terminal.pty.TerminalPtySessions
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.ui.swing.api.TerminalSwingHostServices
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import com.gagik.terminal.ui.swing.api.TerminalViewportListener
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalTheme
import java.awt.Adjustable
import java.awt.BorderLayout
import java.awt.event.AdjustmentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

/**
 * Starts the Lattice standalone terminal application.
 */
fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        LatticeStandaloneApp.start(StandaloneShellCommand.resolve(args.toList()))
    }
}

private object LatticeStandaloneApp {
    private const val APP_TITLE = "Lattice"
    private const val INITIAL_COLUMNS = 100
    private const val INITIAL_ROWS = 30

    fun start(command: List<String>) {
        val frame = JFrame(APP_TITLE)
        val scrollbar = JScrollBar(Adjustable.VERTICAL)
        val scrollbarAdapter = TerminalScrollbarAdapter(scrollbar)
        val settings = StandaloneSettings()
        val terminal =
            TerminalSwingTerminal(
                settingsProvider = { settings.current() },
                hostServices =
                    TerminalSwingHostServices(
                        viewportListener = scrollbarAdapter,
                    ),
            )
        scrollbarAdapter.attach(terminal)

        val session =
            try {
                TerminalPtySessions.start(
                    TerminalPtyOptions(
                        command = command,
                        columns = INITIAL_COLUMNS,
                        rows = INITIAL_ROWS,
                        treatAmbiguousAsWide = settings.current().treatAmbiguousAsWide,
                        eventListener = StandalonePtyEventListener(frame),
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
        frame.jMenuBar = createMenuBar(settings, terminal, session)
        frame.layout = BorderLayout()
        frame.add(createTerminalPanel(terminal, scrollbar), BorderLayout.CENTER)
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent) {
                    session.close()
                }
            },
        )

        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        terminal.requestFocusInWindow()
        session.notifyRenderDirty()
    }

    private fun createTerminalPanel(
        terminal: TerminalSwingTerminal,
        scrollbar: JScrollBar,
    ): JPanel =
        JPanel(BorderLayout()).apply {
            add(terminal, BorderLayout.CENTER)
            add(scrollbar, BorderLayout.EAST)
        }

    private fun createMenuBar(
        settings: StandaloneSettings,
        terminal: TerminalSwingTerminal,
        session: TerminalSession,
    ): JMenuBar {
        val menuBar = JMenuBar()

        val themeMenu = JMenu("Theme")
        TerminalTheme.entries.forEach { theme ->
            val item = JMenuItem(theme.displayName())
            item.addActionListener {
                settings.theme = theme
                terminal.reloadSettings()
                session.setThemePalette(settings.current().palette)
                session.notifyRenderDirty()
            }
            themeMenu.add(item)
        }
        menuBar.add(themeMenu)

        val widthMenu = JMenu("Width")
        val ambiguousWidthItem = JCheckBoxMenuItem("Ambiguous as wide", settings.treatAmbiguousAsWide)
        ambiguousWidthItem.addActionListener {
            settings.treatAmbiguousAsWide = ambiguousWidthItem.isSelected
            terminal.reloadSettings()
        }
        widthMenu.add(ambiguousWidthItem)
        menuBar.add(widthMenu)

        return menuBar
    }

    private fun TerminalTheme.displayName(): String =
        name.lowercase().split("_").joinToString(" ") {
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }
}

private class StandaloneSettings {
    var theme: TerminalTheme = TerminalTheme.CAMPBELL
    var treatAmbiguousAsWide: Boolean = false

    fun current(): TerminalSwingSettings =
        TerminalSwingSettings(
            columns = 100,
            rows = 30,
            palette = theme.createPalette(),
            treatAmbiguousAsWide = treatAmbiguousAsWide,
        )
}

private class TerminalScrollbarAdapter(
    private val scrollbar: JScrollBar,
) : TerminalViewportListener {
    private var terminal: TerminalSwingTerminal? = null
    private var updatingFromTerminal: Boolean = false
    private var historySize: Int = 0

    init {
        scrollbar.unitIncrement = 1
        scrollbar.blockIncrement = 8
        scrollbar.addAdjustmentListener { event ->
            handleAdjustment(event)
        }
    }

    fun attach(terminal: TerminalSwingTerminal) {
        this.terminal = terminal
    }

    override fun viewportChanged(
        historySize: Int,
        scrollbackOffset: Double,
        renderOffset: Int,
        visibleRows: Int,
        requestedRows: Int,
    ) {
        this.historySize = historySize
        val safeVisibleRows = maxOf(1, visibleRows)
        val value = (historySize - scrollbackOffset.toInt()).coerceIn(0, historySize)
        val maximum = historySize + safeVisibleRows

        updatingFromTerminal = true
        try {
            scrollbar.isVisible = historySize > 0
            scrollbar.visibleAmount = safeVisibleRows
            scrollbar.maximum = maximum
            scrollbar.value = value
            scrollbar.blockIncrement = safeVisibleRows
        } finally {
            updatingFromTerminal = false
        }
    }

    private fun handleAdjustment(event: AdjustmentEvent) {
        if (updatingFromTerminal || event.valueIsAdjusting) return
        val targetOffset = (historySize - event.value).coerceIn(0, historySize)
        terminal?.scrollToScrollbackOffset(targetOffset)
    }
}

private class StandalonePtyEventListener(
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
            frame.title = title.ifBlank { "Lattice" }
        }
    }

    override fun listenerFailed(
        session: TerminalSession,
        exception: Exception,
    ) = Unit
}

private object StandaloneShellCommand {
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
