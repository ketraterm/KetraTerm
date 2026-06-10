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
package com.gagik.terminal.standalone.ui

import com.gagik.terminal.pty.TerminalPtyEventListener
import com.gagik.terminal.pty.TerminalPtyOptions
import com.gagik.terminal.pty.TerminalPtySessions
import com.gagik.terminal.render.api.TerminalColorPalette
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.standalone.profile.StandaloneTerminalProfile
import com.gagik.terminal.ui.swing.api.TerminalSwingHostServices
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import java.awt.Adjustable
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.border.EmptyBorder

/**
 * Owns one terminal pane in the standalone host.
 *
 * A pane is the lifecycle unit behind tabs and future split leaves: it contains
 * one reusable Swing terminal component, one local PTY-backed session, and
 * pane-local viewport chrome such as the scrollbar.
 */
internal class LatticeTerminalPane private constructor(
    val profile: StandaloneTerminalProfile,
    val terminal: TerminalSwingTerminal,
    val session: TerminalSession,
    val component: JPanel,
) {
    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    fun reloadSettings(palette: TerminalColorPalette) {
        terminal.reloadSettings()
        session.setThemePalette(palette)
        session.notifyRenderDirty()
    }

    fun close() {
        terminal.unbind()
        session.close()
    }

    internal companion object {
        fun start(
            profile: StandaloneTerminalProfile,
            settings: StandaloneTerminalSettings,
            eventListener: TerminalPtyEventListener,
        ): LatticeTerminalPane {
            val settingsSnapshot = settings.current()
            val scrollbar = JScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = TerminalScrollbarAdapter(scrollbar)
            val terminal =
                TerminalSwingTerminal(
                    settingsProvider = { settings.current() },
                    hostServices =
                        TerminalSwingHostServices(
                            viewportListener = scrollbarAdapter,
                        ),
                )
            scrollbarAdapter.attach(terminal)
            configureScrollbar(scrollbar)

            val session =
                TerminalPtySessions.start(
                    TerminalPtyOptions(
                        command = profile.command,
                        environment = TerminalPtyOptions.defaultEnvironment() + profile.environment,
                        workingDirectory = profile.workingDirectory ?: DEFAULT_WORKING_DIRECTORY,
                        columns = settingsSnapshot.columns,
                        rows = settingsSnapshot.rows,
                        treatAmbiguousAsWide = settingsSnapshot.treatAmbiguousAsWide,
                        eventListener = eventListener,
                    ),
                )
            terminal.bind(session)

            return LatticeTerminalPane(
                profile = profile,
                terminal = terminal,
                session = session,
                component = terminalPanel(terminal, scrollbar),
            ).also {
                session.notifyRenderDirty()
            }
        }

        private fun terminalPanel(
            terminal: TerminalSwingTerminal,
            scrollbar: JScrollBar,
        ): JPanel =
            JPanel(BorderLayout()).apply {
                background = LatticeChrome.TERMINAL_BACKGROUND
                border = EmptyBorder(6, 10, 10, 10)
                terminal.border = null
                add(terminal, BorderLayout.CENTER)
                add(scrollbar, BorderLayout.EAST)
            }

        private fun configureScrollbar(scrollbar: JScrollBar) {
            scrollbar.unitIncrement = 1
            scrollbar.blockIncrement = 8
            scrollbar.preferredSize = LatticeChrome.SCROLLBAR_SIZE
            scrollbar.ui = LatticeScrollBarUi()
            scrollbar.isVisible = false
            scrollbar.isFocusable = false
        }

        private val DEFAULT_WORKING_DIRECTORY: Path = Path.of(System.getProperty("user.home"))
    }
}
