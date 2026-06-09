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

import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import java.awt.Adjustable
import java.awt.BorderLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*

/**
 * Creates and wires the standalone terminal window chrome.
 */
internal class LatticeWindowFactory(
    private val settings: StandaloneTerminalSettings,
) {
    private val scrollbar = JScrollBar(Adjustable.VERTICAL)
    private val scrollbarAdapter = TerminalScrollbarAdapter(scrollbar)
    val viewportListener = scrollbarAdapter

    fun createFrame(terminal: TerminalSwingTerminal): JFrame {
        scrollbarAdapter.attach(terminal)
        configureScrollbar()

        return JFrame(LatticeChrome.APP_TITLE).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            contentPane = terminalPanel(terminal)
            background = LatticeChrome.SURFACE
        }
    }

    fun attachSession(
        frame: JFrame,
        terminal: TerminalSwingTerminal,
        session: TerminalSession,
    ) {
        frame.jMenuBar = LatticeMenuBarFactory(settings).create(terminal, session)
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent) {
                    session.close()
                }
            },
        )
    }

    private fun terminalPanel(terminal: TerminalSwingTerminal): JPanel =
        JPanel(BorderLayout()).apply {
            background = LatticeChrome.SURFACE
            border = BorderFactory.createLineBorder(LatticeChrome.BORDER)
            add(terminal, BorderLayout.CENTER)
            add(scrollbar, BorderLayout.EAST)
        }

    private fun configureScrollbar() {
        scrollbar.unitIncrement = 1
        scrollbar.blockIncrement = 8
        scrollbar.preferredSize = LatticeChrome.SCROLLBAR_SIZE
        scrollbar.ui = LatticeScrollBarUi()
        scrollbar.isVisible = false
        scrollbar.isFocusable = false
    }
}
