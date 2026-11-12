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
package io.github.ketraterm.examples.suggestions

import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.workspace.TerminalProfileKind
import io.github.ketraterm.workspace.TerminalProfileRegistry
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.*

@Tag("desktop")
class SuggestionExampleDesktopTest {
    @Test
    fun `real shell starts in the example and window disposal waits for workspace shutdown`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val profile =
            TerminalProfileRegistry().availableProfiles().firstOrNull {
                it.kind in
                    setOf(
                        TerminalProfileKind.POWERSHELL,
                        TerminalProfileKind.BASH,
                        TerminalProfileKind.ZSH,
                        TerminalProfileKind.GIT_BASH,
                    )
            }
        assumeTrue(profile != null, "Example smoke test requires PowerShell, Bash, or zsh")
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val (frame, status) =
            onEdt {
                val window = SuggestionExampleWindow(assertNotNull(profile).command)
                val frame =
                    Frame
                        .getFrames()
                        .filterIsInstance<JFrame>()
                        .single { it.title == "KetraTerm · Independent suggestion providers" }
                val status = components(frame).filterIsInstance<JLabel>().single { it.text == "Starting shell…" }
                status.addPropertyChangeListener("text") { if (!status.text.startsWith("Starting")) ready.countDown() }
                frame.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosed(event: WindowEvent) {
                            closed.countDown()
                        }
                    },
                )
                window.show()
                frame to status
            }
        try {
            assertTrue(ready.await(30, TimeUnit.SECONDS), "Example startup must complete")
            onEdt {
                assertContains(status.text, "Try echo ext")
                assertEquals(1, components(frame).filterIsInstance<SwingTerminal>().count())
                val toggles = components(frame).filterIsInstance<JCheckBox>().toList()
                assertEquals(listOf("Automatic popup", "Automatic inline"), toggles.map { it.text })
                assertTrue(toggles.all { it.isSelected })
                toggles.first().doClick()
                assertFalse(toggles.first().isSelected)
                assertTrue(toggles.last().isSelected)
                val image = BufferedImage(frame.contentPane.width, frame.contentPane.height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try {
                    frame.contentPane.printAll(graphics)
                } finally {
                    graphics.dispose()
                }
                val path = Path.of("build", "test-artifacts", "desktopTest", "suggestion-example.png")
                Files.createDirectories(path.parent)
                ImageIO.write(image, "png", path.toFile())
            }
        } finally {
            onEdt { frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING)) }
            assertTrue(closed.await(30, TimeUnit.SECONDS), "Window must close after workspace/process cleanup")
        }
    }

    private fun components(root: Component): Sequence<Component> =
        sequence {
            yield(root)
            if (root is Container) root.components.forEach { yieldAll(components(it)) }
        }

    private fun <T> onEdt(action: () -> T): T {
        val work = FutureTask(action)
        SwingUtilities.invokeAndWait(work)
        return work.get()
    }
}
