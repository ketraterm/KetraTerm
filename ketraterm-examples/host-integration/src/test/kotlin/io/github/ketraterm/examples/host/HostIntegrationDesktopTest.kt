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
package io.github.ketraterm.examples.host

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@Tag("desktop")
class HostIntegrationDesktopTest {
    @Test
    fun `real shell binds before output resizes reloads and closes across two windows`() =
        runBlocking {
            check(!GraphicsEnvironment.isHeadless()) { "Run the desktopTest task with an active desktop." }
            val command =
                if (System.getProperty("os.name").startsWith("Windows")) {
                    listOf("powershell.exe", "-NoLogo", "-NoProfile")
                } else {
                    listOf("/bin/bash", "--noprofile", "--norc", "-i")
                }
            val profile = hostProfile(command)
            repeat(2) { iteration ->
                lateinit var ownedSession: TerminalSession
                withHostSession(profile) { session ->
                    ownedSession = session
                    val bound = CompletableDeferred<Unit>()
                    var pane: HostTerminalPane? = null
                    var window: JFrame? = null
                    try {
                        withContext(Dispatchers.Swing) {
                            val content = HostTerminalPane(session, profile)
                            pane = content
                            window =
                                JFrame("KetraTerm host lifecycle test").apply {
                                    contentPane = content.component
                                    pack()
                                    isVisible = true
                                }
                            content.terminal.bind(session) { failure ->
                                if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                            }
                        }
                        val content = checkNotNull(pane)
                        withTimeout(10.seconds) { bound.await() }
                        session.start()
                        awaitCondition { session.activeShellCommandLine() != null }
                        val marker = "host_lifecycle_${iteration}_complete"
                        assertEquals(
                            TerminalInputAdmission.ACCEPTED,
                            session.submitInput(listOf(TerminalPasteEvent("echo $marker"), TerminalKeyEvent(key = TerminalKey.ENTER))),
                        )
                        awaitCondition { content.lastOutput()?.text?.contains(marker) == true }
                        val previousGrid = withContext(Dispatchers.Swing) { content.terminal.visibleGridSize() }
                        withContext(Dispatchers.Swing) {
                            checkNotNull(window).let {
                                it.setSize(it.width + 73, it.height + 41)
                                it.validate()
                            }
                        }
                        val lastCell = Rectangle()
                        awaitCondition {
                            val visibleGrid = withContext(Dispatchers.Swing) { content.terminal.visibleGridSize() }
                            var sessionMatches = false
                            session.readRenderFrame { frame ->
                                sessionMatches = frame.columns == visibleGrid.width && frame.rows == visibleGrid.height
                            }
                            sessionMatches &&
                                visibleGrid.width > previousGrid.width &&
                                visibleGrid.height > previousGrid.height &&
                                withContext(Dispatchers.Swing) {
                                    content.terminal.visibleGridSize() == visibleGrid &&
                                        content.terminal.copyCellBounds(visibleGrid.width - 1, visibleGrid.height - 1, lastCell) &&
                                        lastCell.width > 0 &&
                                        lastCell.height > 0
                                }
                        }
                        val reloaded = CompletableDeferred<Unit>()
                        withContext(Dispatchers.Swing) {
                            content.updateSettings(lightTheme = true) { failure ->
                                if (failure == null) reloaded.complete(Unit) else reloaded.completeExceptionally(failure)
                            }
                        }
                        withTimeout(10.seconds) { reloaded.await() }
                        assertContains(assertNotNull(content.lastOutput()).text, marker)
                        withContext(Dispatchers.Swing) { content.close() }
                        assertFalse(session.isClosed)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Swing) {
                            try {
                                pane?.close()
                            } finally {
                                window?.dispose()
                            }
                        }
                    }
                }
                assertTrue(ownedSession.isClosed)
            }
        }
}
