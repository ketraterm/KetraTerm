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

import com.gagik.terminal.pty.TerminalPtyOptions
import com.gagik.terminal.pty.TerminalPtySessions
import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.standalone.pty.StandalonePtyEventListener
import com.gagik.terminal.standalone.shell.StandaloneShellCommand
import com.gagik.terminal.standalone.ui.LatticeWindowFactory
import com.gagik.terminal.ui.swing.api.TerminalSwingHostServices
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Starts the Lattice standalone terminal application.
 */
fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
        LatticeStandaloneApp.start(args.toList())
    }
}

private object LatticeStandaloneApp {
    private const val INITIAL_COLUMNS = 100
    private const val INITIAL_ROWS = 30

    fun start(args: List<String>) {
        StandaloneLookAndFeel.install()

        val settings = StandaloneTerminalSettings()
        val windowFactory = LatticeWindowFactory(settings)
        val terminal =
            TerminalSwingTerminal(
                settingsProvider = { settings.current() },
                hostServices =
                    TerminalSwingHostServices(
                        viewportListener = windowFactory.viewportListener,
                    ),
            )
        val frame = windowFactory.createFrame(terminal)
        val eventListener = StandalonePtyEventListener(frame)

        val session =
            try {
                TerminalPtySessions.start(
                    TerminalPtyOptions(
                        command = StandaloneShellCommand.resolve(args),
                        columns = INITIAL_COLUMNS,
                        rows = INITIAL_ROWS,
                        treatAmbiguousAsWide = settings.current().treatAmbiguousAsWide,
                        eventListener = eventListener,
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

        windowFactory.attachSession(frame, terminal, session)
        terminal.bind(session)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        terminal.requestFocusInWindow()
        session.notifyRenderDirty()
    }
}
