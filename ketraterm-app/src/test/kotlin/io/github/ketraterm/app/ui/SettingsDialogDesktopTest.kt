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

import com.formdev.flatlaf.FlatDarkLaf
import io.github.ketraterm.app.config.KetraTermConfig
import io.github.ketraterm.app.config.KetraTermConfigManager
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.app.config.SuggestionKeyBindings
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.ui.swing.settings.SwingPromptDecoration
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAutomaticMode
import io.github.ketraterm.workspace.TerminalProfileRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Tag
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("desktop")
class SettingsDialogDesktopTest {
    @Test
    fun `suggestions page contains only its controls and fits the standard dialog`() {
        withDialog(configure = {
            it.copy(shellSuggestionsEnabled = true, acceptSelectedSuggestionWithEnter = true, persistentSuggestionLearningEnabled = false)
        }) { _, dialog, _ ->
            onEdt {
                dialog.setSize(820, 600)
                val behavior = components(dialog).single { it.name == "settings.behavior.page" }
                val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, behavior) as JViewport
                selectPage(dialog, "settings.behavior")
                dialog.validate()
                viewport.viewPosition = Point(0, 80)
                selectPage(dialog, "settings.suggestions")
                dialog.validate()
                val page = components(dialog).single { it.name == "settings.suggestions.page" }
                assertTrue(page.isVisible)
                assertEquals(Point(0, 0), viewport.viewPosition)
                assertEquals(3, components(page).filterIsInstance<ShortcutEditor>().count())
                assertTrue(components(page).none { it is JTextField })
                assertTrue(components(behavior).none { it is ShortcutEditor })
                val reset = button(dialog, "Reset learning…")
                val resetBounds = SwingUtilities.convertRectangle(reset.parent, reset.bounds, viewport.view)
                assertTrue(viewport.viewRect.contains(resetBounds), "All suggestion controls should fit without scrolling: $resetBounds")
                val image = BufferedImage(dialog.contentPane.width, dialog.contentPane.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    dialog.contentPane.printAll(graphics)
                } finally {
                    graphics.dispose()
                }
                val path = Path.of("build", "reports", "screenshots", "suggestions-settings.png")
                Files.createDirectories(path.parent)
                ImageIO.write(image, "png", path.toFile())
            }
        }
    }

    @Test
    fun `custom shortcut chips and recording controls remain accessible after resizing`() {
        withDialog { _, dialog, _ ->
            val editor =
                onEdt {
                    selectPage(dialog, "settings.suggestions")
                    components(dialog).filterIsInstance<ShortcutEditor>().single { it.name == "popupAcceptKeys" }
                }
            val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            for (width in listOf(820, 1_100, 820)) {
                for (keys in listOf(listOf(KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F), (KeyEvent.VK_A..KeyEvent.VK_F).toList())) {
                    onEdt {
                        dialog.setSize(width, 600)
                        editor.shortcuts = keys.map { KeyStroke.getKeyStroke(it, modifiers) }.toSet()
                        dialog.validate()
                    }
                    onEdt {
                        dialog.validate()
                        editor.components
                            .filterIsInstance<JButton>()
                            .single { it.toolTipText == "Add shortcut" }
                            .doClick()
                        dialog.validate()
                    }
                    onEdt {
                        dialog.validate()
                        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, editor) as JViewport
                        val controls = editor.components.filter { it.isVisible }
                        assertTrue(controls.filterIsInstance<JButton>().any { it.text == "Cancel" })
                        val rectangles = controls.map { SwingUtilities.convertRectangle(it.parent, it.bounds, viewport.view) }
                        val editorBounds = SwingUtilities.convertRectangle(editor.parent, editor.bounds, viewport.view)
                        for (bounds in rectangles) {
                            assertTrue(viewport.viewRect.contains(bounds), "Shortcut control must fit at width $width: $bounds")
                            assertTrue(editorBounds.contains(bounds), "The shortcut row must reserve enough height: $bounds")
                        }
                        for (first in rectangles.indices) {
                            for (second in first + 1 until rectangles.size) {
                                assertFalse(rectangles[first].intersects(rectangles[second]), "Shortcut controls must not overlap")
                            }
                        }
                        editor.components
                            .filterIsInstance<JButton>()
                            .single { it.text == "Cancel" }
                            .doClick()
                        dialog.validate()
                    }
                }
            }
        }
    }

    @Test
    fun `Apply preserves disabled suggestion controls after saving`() {
        withDialog { _, dialog, _ ->
            for (disableMaster in listOf(false, true)) {
                val saved = CountDownLatch(1)
                onEdt {
                    val mode = components(dialog).filterIsInstance<JComboBox<*>>().single { it.name == "popupSuggestionMode" }
                    mode.selectedIndex = 0
                    if (disableMaster) {
                        components(dialog).filterIsInstance<JCheckBox>().single { it.text == "Enable suggestions" }.doClick()
                    }
                    button(dialog, "OK").addPropertyChangeListener("enabled") { if (it.newValue == true) saved.countDown() }
                    button(dialog, "Apply").doClick()
                }
                assertTrue(saved.await(5, TimeUnit.SECONDS))
                onEdt {
                    assertTrue(components(dialog).filterIsInstance<ShortcutEditor>().all { !it.isEnabled })
                    val mode = components(dialog).filterIsInstance<JComboBox<*>>().single { it.name == "popupSuggestionMode" }
                    assertEquals(!disableMaster, mode.isEnabled)
                    val learning = components(dialog).filterIsInstance<JCheckBox>().single { it.text == "Remember suggestion rankings" }
                    assertEquals(!disableMaster, learning.isEnabled)
                }
            }
        }
    }

    @Test
    fun `removing the Enter chip disables Enter acceptance without changing other bindings`() {
        withDialog(configure = { it.copy(acceptSelectedSuggestionWithEnter = true) }) { settings, dialog, closed ->
            onEdt {
                val editor = components(dialog).filterIsInstance<ShortcutEditor>().single { it.name == "popupAcceptKeys" }
                val label = KeyEvent.getKeyText(KeyEvent.VK_ENTER) + " ×"
                editor.components
                    .filterIsInstance<JButton>()
                    .single { it.text == label }
                    .doClick()
                assertEquals(setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)), editor.shortcuts)
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(settings.config.acceptSelectedSuggestionWithEnter)
            assertEquals(setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)), settings.config.suggestionKeyBindings.popupAccept)
            assertEquals(settings.config, KetraTermConfigManager(settings.configPath).load())
        }
    }

    @Test
    fun `Tab and Enter shortcut events are consumed without saving the dialog`() {
        withDialog(
            configure = { it.copy(suggestionKeyBindings = SuggestionKeyBindings(popupAccept = emptySet())) },
        ) { settings, dialog, closed ->
            onEdt {
                selectPage(dialog, "settings.suggestions")
                val editor = components(dialog).filterIsInstance<ShortcutEditor>().single { it.name == "popupAcceptKeys" }
                for (code in listOf(KeyEvent.VK_TAB, KeyEvent.VK_ENTER)) {
                    val add = editor.components.filterIsInstance<JButton>().single { it.toolTipText == "Add shortcut" }
                    add.doClick()
                    assertFalse(add.focusTraversalKeysEnabled)
                    val pressed = KeyEvent(add, KeyEvent.KEY_PRESSED, 0L, 0, code, KeyEvent.CHAR_UNDEFINED)
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(add, pressed)
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(
                        add,
                        KeyEvent(add, KeyEvent.KEY_RELEASED, 0L, 0, code, KeyEvent.CHAR_UNDEFINED),
                    )
                    assertTrue(pressed.isConsumed)
                    assertTrue(KeyStroke.getKeyStroke(code, 0) in editor.shortcuts)
                    assertTrue(add.focusTraversalKeysEnabled)
                    assertEquals(1L, closed.count)
                }
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(settings.config.acceptSelectedSuggestionWithEnter)
        }
    }

    @Test
    fun `automatic popup modes preserve disabled selection and persist through the settings dialog`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val commandNames = components(dialog).filterIsInstance<JCheckBox>().single { it.text == "Include command names" }
                val mode = components(dialog).filterIsInstance<JComboBox<*>>().single { it.name == "popupSuggestionMode" }
                assertEquals("On request", mode.selectedItem?.toString())
                assertTrue(commandNames.isSelected)
                assertFalse(commandNames.isVisible)
                mode.selectedIndex = 2
                commandNames.doClick()
                assertFalse(commandNames.isSelected)
                assertTrue(button(dialog, "Apply").isEnabled)
                mode.selectedIndex = 0
                assertFalse(commandNames.isEnabled)
                assertFalse(commandNames.isVisible)
                mode.selectedIndex = 2
                assertTrue(commandNames.isEnabled)
                assertFalse(commandNames.isSelected)
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(
                SwingShellSuggestionAutomaticMode.PARAMETERS_ONLY,
                settings.config.shellSuggestionAutomaticMode,
            )
            assertEquals(settings.config.shellSuggestionAutomaticMode, settings.current().shellSuggestionAutomaticMode)
        }
    }

    @Test
    fun `command alert controls reset and persist independently of completion`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val enabled =
                    components(dialog).filterIsInstance<JCheckBox>().single {
                        it.text ==
                            "Notify when long-running commands finish"
                    }
                val desktop = components(dialog).filterIsInstance<JCheckBox>().single { it.text == "Desktop notifications" }
                val threshold = components(dialog).filterIsInstance<JSpinner>().single { it.name == "commandFinishedAlertThresholdSeconds" }
                assertFalse(enabled.isSelected)
                assertTrue(desktop.isSelected)
                assertEquals(10, threshold.value)
                enabled.doClick()
                threshold.value = 60
                button(dialog, "Reset to Defaults").doClick()
                assertFalse(enabled.isSelected)
                assertTrue(desktop.isSelected)
                assertEquals(10, threshold.value)
                enabled.doClick()
                threshold.value = 25
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(settings.config.commandFinishedAlertsEnabled)
            assertEquals(25, settings.config.commandFinishedAlertThresholdSeconds)
            assertTrue(settings.config.smartSuggestionsEnabled)
        }
    }

    @Test
    fun `prompt style selector resets and persists its choice`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val combo =
                    components(dialog).filterIsInstance<JComboBox<*>>().single {
                        it.selectedItem is SwingPromptDecoration
                    }
                assertEquals(
                    listOf(SwingPromptDecoration.GUTTER, SwingPromptDecoration.DIVIDER, SwingPromptDecoration.NONE),
                    (0 until combo.itemCount).map { combo.getItemAt(it) },
                )
                combo.selectedItem = SwingPromptDecoration.NONE
                button(dialog, "Reset to Defaults").doClick()
                assertEquals(SwingPromptDecoration.GUTTER, combo.selectedItem)
                combo.selectedItem = SwingPromptDecoration.DIVIDER
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(SwingPromptDecoration.DIVIDER, settings.config.promptDecoration)
            assertEquals(SwingPromptDecoration.DIVIDER, settings.current().promptDecoration)
        }
    }

    @Test
    fun `clipboard settings offer three permissions and preserve independent choices`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val combos =
                    components(dialog)
                        .filterIsInstance<JComboBox<*>>()
                        .filter { it.selectedItem is TerminalClipboardPermission }
                        .toList()
                assertEquals(2, combos.size)
                val expected =
                    listOf(TerminalClipboardPermission.DENY, TerminalClipboardPermission.PROMPT, TerminalClipboardPermission.ALLOW)
                for (combo in combos) {
                    assertEquals(expected, (0 until combo.itemCount).map { combo.getItemAt(it) })
                }
                assertEquals(TerminalClipboardPermission.DENY, combos[0].selectedItem)
                assertEquals(TerminalClipboardPermission.DENY, combos[1].selectedItem)
                combos[0].selectedItem = TerminalClipboardPermission.PROMPT
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(TerminalClipboardPermission.PROMPT, settings.config.clipboardWrite)
            assertEquals(TerminalClipboardPermission.DENY, settings.config.clipboardRead)
        }
    }

    @Test
    fun `paste handling offers two choices and persists control filtering`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val combo =
                    components(dialog).filterIsInstance<JComboBox<*>>().single {
                        it.selectedItem?.toString() == "Preserve text"
                    }
                assertEquals(
                    listOf("Preserve text", "Remove control characters"),
                    (0 until combo.itemCount).map { combo.getItemAt(it).toString() },
                )
                combo.selectedIndex = 1
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF, settings.config.pasteControlPolicy)
            assertEquals(settings.config.pasteControlPolicy, settings.current().pasteControlPolicy)
        }
    }

    @Test
    fun `process title checkbox persists its selection`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val checkbox =
                    components(dialog).filterIsInstance<JCheckBox>().single {
                        it.text == "Show running process in tab titles"
                    }
                assertTrue(checkbox.isSelected)
                checkbox.doClick()
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(settings.config.showForegroundProcessName)
        }
    }

    @Test
    fun `startup command field persists exact text`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val field =
                    components(dialog).filterIsInstance<JTextField>().first {
                        it.toolTipText?.startsWith("Run once when a new shell is ready") == true
                    }
                field.text = "  echo 'ready'  "
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals("  echo 'ready'  ", settings.config.startupCommand)
        }
    }

    @Test
    fun `OK saves off EDT and waits for the single in-flight save before closing`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("ketraterm-settings-dialog")
        val path = directory.resolve("config.toml")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val savedOnEdt = AtomicBoolean(true)
        val manager = KetraTermConfigManager(path)
        val settings =
            KetraTermSettings(manager) { snapshot ->
                savedOnEdt.set(SwingUtilities.isEventDispatchThread())
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                manager.save(snapshot)
            }
        val (frame, dialog) =
            onEdt {
                val frame = JFrame()
                val dialog =
                    SettingsDialog(
                        frame,
                        settings,
                        TerminalProfileRegistry(executableExists = { false }),
                    ) { error("Unexpected learning reset") }
                dialog.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosed(event: WindowEvent) {
                            closed.countDown()
                        }
                    },
                )
                dialog.pack()
                val fontSize =
                    components(dialog).filterIsInstance<JSpinner>().first {
                        val model = it.model as SpinnerNumberModel
                        model.minimum == KetraTermConfig.FONT_SIZE_MIN && model.maximum == KetraTermConfig.FONT_SIZE_MAX
                    }
                fontSize.value = 24
                button(dialog, "OK").doClick()
                frame to dialog
            }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            onEdt {
                assertFalse(button(dialog, "OK").isEnabled)
                assertFalse(button(dialog, "Apply").isEnabled)
                assertFalse(button(dialog, "Cancel").isEnabled)
                dialog.dispose()
                assertTrue(dialog.isDisplayable)
            }
            assertFalse(savedOnEdt.get())
            assertEquals(KetraTermConfig.DEFAULT_FONT_SIZE, settings.config.fontSize)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(24, settings.config.fontSize)
            assertEquals(settings.config, manager.load())
        } finally {
            release.countDown()
            onEdt {
                dialog.dispose()
                frame.dispose()
            }
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `dialog preserves configured clipboard permissions and suggestion preferences on unrelated save`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                theme.selectedItem = TerminalTheme.NORD
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(TerminalTheme.NORD.id, settings.config.theme)
            assertEquals(TerminalClipboardPermission.DENY, settings.config.clipboardWrite)
            assertEquals(TerminalClipboardPermission.DENY, settings.config.clipboardRead)
            assertTrue(settings.config.smartSuggestionsEnabled)
            assertFalse(settings.config.shellSuggestionsEnabled)
            assertFalse(settings.config.acceptSelectedSuggestionWithEnter)
            assertTrue(settings.config.persistentSuggestionLearningEnabled)
        }
    }

    @Test
    fun `reset restores the actual default theme and suggestion preferences`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                button(dialog, "Reset to Defaults").doClick()
                val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                assertEquals(TerminalTheme.fromId(KetraTermConfig.DEFAULT_THEME), theme.selectedItem)
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(KetraTermConfig.DEFAULT_THEME, settings.config.theme)
            assertEquals(TerminalClipboardPermission.PROMPT, settings.config.clipboardRead)
            assertEquals(TerminalClipboardPermission.PROMPT, settings.createHostPolicy().clipboardPolicy.readPermission)
            assertEquals(TerminalClipboardPermission.ALLOW, settings.config.clipboardWrite)
            assertTrue(settings.config.smartSuggestionsEnabled)
            assertTrue(settings.config.shellSuggestionsEnabled)
            assertTrue(settings.config.acceptSelectedSuggestionWithEnter)
            assertFalse(settings.config.persistentSuggestionLearningEnabled)
        }
    }

    @Test
    fun `suggestion controls track changes and persist independently`() {
        withDialog { settings, dialog, closed ->
            onEdt {
                val checkboxes = components(dialog).filterIsInstance<JCheckBox>().associateBy { it.text }
                val smart = checkboxes.getValue("Enable suggestions")
                val inline = checkboxes.getValue("Inline suggestions")
                val persistence = checkboxes.getValue("Remember suggestion rankings")
                val popup = components(dialog).filterIsInstance<JComboBox<*>>().single { it.name == "popupSuggestionMode" }
                val popupKeys = components(dialog).filterIsInstance<ShortcutEditor>().single { it.name == "popupAcceptKeys" }
                assertTrue(smart.isSelected)
                assertEquals("On request", popup.selectedItem?.toString())
                assertFalse(inline.isSelected)
                assertFalse(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0) in popupKeys.shortcuts)
                assertTrue(persistence.isSelected)
                assertFalse(button(dialog, "Apply").isEnabled)
                inline.doClick()
                val inlineKeys = components(dialog).filterIsInstance<ShortcutEditor>().single { it.name == "inlineAcceptKeys" }
                inlineKeys.shortcuts = setOf(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK))
                popupKeys.shortcuts = popupKeys.shortcuts + KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
                popup.selectedIndex = 0
                persistence.doClick()
                smart.doClick()
                assertFalse(inline.isEnabled)
                assertTrue(button(dialog, "Apply").isEnabled)
                button(dialog, "OK").doClick()
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(settings.config.smartSuggestionsEnabled)
            assertTrue(settings.config.inlineSuggestionsEnabled)
            assertFalse(settings.config.popupSuggestionsEnabled)
            assertEquals(
                setOf(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK)),
                settings.config.suggestionKeyBindings.inlineAccept,
            )
            assertFalse(settings.config.shellSuggestionsEnabled)
            assertTrue(settings.config.acceptSelectedSuggestionWithEnter)
            assertFalse(settings.config.persistentSuggestionLearningEnabled)
        }
    }

    @Test
    fun `opening settings preserves shell command spelling instead of replacing it with a discovered path`() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows",
                environment = mapOf("SystemRoot" to "C:/Windows"),
                executableExists = { it.fileName.toString().equals("powershell.exe", ignoreCase = true) },
            )
        for (shellPath in listOf("powershell.exe", "PowerShell.EXE")) {
            withDialog(profileRegistry = registry, shellPath = shellPath) { settings, dialog, closed ->
                onEdt {
                    assertFalse(button(dialog, "Apply").isEnabled)
                    assertTrue(components(dialog).filterIsInstance<JTextField>().any { it.text == shellPath })
                    val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                    theme.selectedItem = TerminalTheme.NORD
                    button(dialog, "OK").doClick()
                }
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertEquals(shellPath, settings.config.shellPath)
            }
        }
    }

    @Test
    fun `dialog preserves unavailable fonts and configured font name casing`() {
        val installed = SwingSettings.getMonospaceFontFamilies().first()
        val differentCase = installed.lowercase().takeIf { it != installed } ?: installed.uppercase()
        for (fontFamily in listOf("Uninstalled Terminal Font", differentCase)) {
            withDialog(fontFamily = fontFamily) { settings, dialog, closed ->
                onEdt {
                    assertFalse(button(dialog, "Apply").isEnabled)
                    val theme = components(dialog).filterIsInstance<JComboBox<*>>().first { it.selectedItem is TerminalTheme }
                    theme.selectedItem = TerminalTheme.NORD
                    button(dialog, "OK").doClick()
                }
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertEquals(fontFamily, settings.config.fontFamily)
            }
        }
    }

    @Test
    fun `learning reset requires confirmation and waits for persistence before reporting success`() {
        var calls = 0
        val reset = CompletableDeferred<Unit>()
        withDialog(resetLearning = {
            calls++
            reset
        }) { settings, dialog, _ ->
            settings.update(settings.config.copy(smartSuggestionsEnabled = false, persistentSuggestionLearningEnabled = false))
            onEdt {
                assertTrue(button(dialog, "Reset learning…").isEnabled)
                respondToReset(dialog, "Cancel")
                assertEquals(0, calls)
                respondToReset(dialog, "Reset")
                assertEquals(1, calls)
                assertFalse(button(dialog, "Reset learning…").isEnabled)
                assertTrue(components(dialog).filterIsInstance<JLabel>().any { it.text == "Clearing learned suggestions..." })
            }
            reset.complete(Unit)
            onEdt {
                assertTrue(button(dialog, "Reset learning…").isEnabled)
                assertTrue(components(dialog).filterIsInstance<JLabel>().any { it.text == "Learned suggestions cleared." })
                assertFalse(button(dialog, "Apply").isEnabled)
            }
        }
    }

    @Test
    fun `failed learning reset offers retry and closing settings does not cancel the reset`() {
        var reset = CompletableDeferred<Unit>()
        withDialog(resetLearning = { reset }) { _, dialog, _ ->
            onEdt { respondToReset(dialog, "Reset") }
            reset.completeExceptionally(IOException("Read-only destination"))
            onEdt {
                assertTrue(button(dialog, "Reset learning…").isEnabled)
                val status =
                    components(
                        dialog,
                    ).filterIsInstance<JLabel>().single { it.text == "Could not clear saved learning. Try again." }
                assertEquals("Read-only destination", status.toolTipText)
                reset = CompletableDeferred()
                respondToReset(dialog, "Reset")
                dialog.dispose()
                assertFalse(reset.isCancelled)
            }
            reset.complete(Unit)
        }
    }

    private fun respondToReset(
        dialog: SettingsDialog,
        choice: String,
    ) {
        val listener =
            AWTEventListener { event ->
                if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED) {
                    val confirmation = event.window as? JDialog
                    if (confirmation?.owner === dialog) {
                        components(confirmation).filterIsInstance<JButton>().single { it.text == choice }.doClick()
                    }
                }
            }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK)
        try {
            button(dialog, "Reset learning…").doClick()
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }

    private fun withDialog(
        profileRegistry: TerminalProfileRegistry = TerminalProfileRegistry(executableExists = { false }),
        shellPath: String = KetraTermConfig.DEFAULT_SHELL_PATH,
        fontFamily: String = KetraTermConfig.DEFAULT_FONT_FAMILY,
        resetLearning: () -> Deferred<Unit> = { error("Unexpected learning reset") },
        configure: (KetraTermConfig) -> KetraTermConfig = { it },
        block: (KetraTermSettings, SettingsDialog, CountDownLatch) -> Unit,
    ) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = Files.createTempDirectory("ketraterm-settings-dialog")
        val path = directory.resolve("config.toml")
        val manager = KetraTermConfigManager(path)
        manager.save(
            configure(
                KetraTermConfig(
                    shellPath = shellPath,
                    fontFamily = fontFamily,
                    theme = TerminalTheme.TOKYO_NIGHT.id,
                    smartSuggestionsEnabled = true,
                    shellSuggestionsEnabled = false,
                    acceptSelectedSuggestionWithEnter = false,
                    persistentSuggestionLearningEnabled = true,
                    clipboardWrite = TerminalClipboardPermission.DENY,
                    clipboardRead = TerminalClipboardPermission.DENY,
                ),
            ),
        )
        val settings = KetraTermSettings(manager)
        val closed = CountDownLatch(1)
        val previousLookAndFeel = onEdt { UIManager.getLookAndFeel().also { UIManager.setLookAndFeel(FlatDarkLaf()) } }
        val (frame, dialog) =
            onEdt {
                val frame = JFrame()
                val dialog = SettingsDialog(frame, settings, profileRegistry, resetLearning)
                dialog.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowClosed(event: WindowEvent) {
                            closed.countDown()
                        }
                    },
                )
                dialog.pack()
                frame to dialog
            }
        try {
            block(settings, dialog, closed)
        } finally {
            onEdt {
                dialog.dispose()
                frame.dispose()
                UIManager.setLookAndFeel(previousLookAndFeel)
            }
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun button(
        dialog: SettingsDialog,
        text: String,
    ): JButton = components(dialog).filterIsInstance<JButton>().first { it.text == text }

    private fun selectPage(
        dialog: SettingsDialog,
        name: String,
    ) {
        val category = components(dialog).single { it.name == name }
        category.dispatchEvent(MouseEvent(category, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false))
    }

    private fun components(root: Component): Sequence<Component> =
        sequence {
            yield(root)
            if (root is Container) root.components.forEach { yieldAll(components(it)) }
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
