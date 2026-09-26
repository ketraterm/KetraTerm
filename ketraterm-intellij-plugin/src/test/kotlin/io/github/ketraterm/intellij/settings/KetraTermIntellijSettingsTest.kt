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
package io.github.ketraterm.intellij.settings

import com.intellij.configurationStore.deserialize
import com.intellij.configurationStore.serialize
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardPolicy
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.workspace.config.TerminalConfig
import org.jdom.Element
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * Tests IntelliJ settings persistence mapping without opening an IDE window.
 */
class KetraTermIntellijSettingsTest {
    @Test
    fun `fresh clipboard Ask survives XML round trip while library reads stay denied`() {
        val settings = KetraTermIntellijSettings()
        assertEquals(TerminalClipboardPermission.PROMPT, settings.createHostPolicy().clipboardPolicy.readPermission)
        assertEquals(TerminalClipboardPermission.DENY, TerminalClipboardPolicy().readPermission)
        val xml = requireNotNull(serialize(settings.state))
        assertEquals(
            "prompt",
            xml.getChildren("option").single { it.getAttributeValue("name") == "clipboardRead" }.getAttributeValue("value"),
        )
        val restored = KetraTermIntellijSettings()
        restored.loadState(xml.deserialize(KetraTermIntellijSettings.State::class.java))
        assertEquals(settings.state, restored.state)
    }

    @Test
    fun `legacy XML omission retains Deny through unrelated changes and restart`() {
        val legacyStates =
            listOf(
                Element("State") to KetraTermIntellijSettings.DEFAULT_FONT_SIZE,
                Element("State").addContent(Element("option").setAttribute("name", "fontSize").setAttribute("value", "18")) to 18,
            )
        for ((xml, fontSize) in legacyStates) {
            val settings = KetraTermIntellijSettings()
            val observed = mutableListOf<TerminalClipboardPermission>()
            settings.addChangeListener { observed += settings.createHostPolicy().clipboardPolicy.readPermission }
            settings.loadState(xml.deserialize(KetraTermIntellijSettings.State::class.java))
            assertEquals(fontSize, settings.state.fontSize)
            assertEquals(TerminalClipboardPermission.DENY, settings.createHostPolicy().clipboardPolicy.readPermission)
            assertEquals(listOf(TerminalClipboardPermission.DENY), observed)
            settings.replaceState(settings.state.copy(visualBell = !settings.state.visualBell))
            val saved = requireNotNull(serialize(settings.state))
            assertEquals(
                "deny",
                saved.getChildren("option").single { it.getAttributeValue("name") == "clipboardRead" }.getAttributeValue("value"),
            )
            val restored = KetraTermIntellijSettings()
            restored.loadState(saved.deserialize(KetraTermIntellijSettings.State::class.java))
            assertEquals(settings.state, restored.state)
        }
    }

    @Test
    fun `invalid XML read choices normalize to persisted Deny`() {
        for (invalid in listOf("", "unknown", "allowlist")) {
            val xml =
                Element("State").addContent(
                    Element("option").setAttribute("name", "clipboardRead").setAttribute("value", invalid),
                )
            val settings = KetraTermIntellijSettings()
            settings.loadState(xml.deserialize(KetraTermIntellijSettings.State::class.java))
            assertEquals(TerminalClipboardPermission.DENY, settings.createHostPolicy().clipboardPolicy.readPermission)
            val restored = KetraTermIntellijSettings()
            restored.loadState(requireNotNull(serialize(settings.state)).deserialize(KetraTermIntellijSettings.State::class.java))
            assertEquals(settings.state, restored.state)
        }
    }

    @Test
    fun `every explicit read choice remains in XML even when all other preferences are default`() {
        for (permission in TerminalClipboardPermission.entries) {
            val id = permission.name.lowercase(java.util.Locale.ROOT)
            val settings = KetraTermIntellijSettings()
            settings.replaceState(KetraTermIntellijSettings.State(clipboardRead = id))
            val saved = requireNotNull(serialize(settings.state))
            assertEquals(
                id,
                saved.getChildren("option").single { it.getAttributeValue("name") == "clipboardRead" }.getAttributeValue("value"),
            )
            val restored = KetraTermIntellijSettings()
            restored.loadState(saved.deserialize(KetraTermIntellijSettings.State::class.java))
            assertEquals(permission, restored.createHostPolicy().clipboardPolicy.readPermission)
        }
    }

    @Test
    fun `IDE host denies application window and column mode requests`() {
        val settings = KetraTermIntellijSettings()
        assertEquals(HostControlPolicy.DENY, settings.createHostPolicy().windowManipulationPolicy)
        val swingSettings = KetraTermIntellijSettingsMapper.toSwingSettings(settings.state.copy(themeId = "nord"))
        assertFalse(swingSettings.shellRequestResizeWindow)
    }

    @Test
    fun `paste policies survive state reload and map to embedding settings`() {
        val service = KetraTermIntellijSettings()
        assertEquals("preserve", service.state.pasteSanitization)
        val policies =
            listOf(
                "preserve" to PasteControlPolicy.PRESERVE,
                "raw" to PasteControlPolicy.PRESERVE,
                "strip-c0" to PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                "normalize-line-endings" to PasteControlPolicy.PRESERVE,
            )
        for ((id, policy) in policies) {
            service.loadState(service.state.copy(themeId = "nord", pasteSanitization = " ${id.uppercase(java.util.Locale.ROOT)} "))
            assertEquals(if (id == "strip-c0") "strip-c0" else "preserve", service.state.pasteSanitization)
            service.replaceState(service.state.copy(visualBell = !service.state.visualBell))
            val reloaded = KetraTermIntellijSettings()
            reloaded.loadState(service.state)
            assertEquals(policy, KetraTermIntellijSettingsMapper.toSwingSettings(reloaded.state).pasteControlPolicy)
        }
        service.loadState(service.state.copy(pasteSanitization = "unknown"))
        assertEquals("preserve", service.state.pasteSanitization)
        assertEquals(PasteControlPolicy.PRESERVE, KetraTermIntellijSettingsMapper.toSwingSettings(service.state).pasteControlPolicy)
    }

    @Test
    fun `project JDK injection defaults on and survives platform state reload`() {
        val service = KetraTermIntellijSettings()
        assertTrue(service.state.addProjectJdkToPath)

        service.loadState(service.state.copy(addProjectJdkToPath = false))
        service.replaceState(service.state.copy(environmentVariables = "JAVA_HOME=/custom/jdk"))

        assertFalse(service.state.addProjectJdkToPath)
        assertEquals("JAVA_HOME=/custom/jdk", service.state.environmentVariables)
    }

    @Test
    fun `platform load and UI updates publish normalized state before notifying listeners`() {
        val service = KetraTermIntellijSettings()
        val observed = mutableListOf<KetraTermIntellijSettings.State>()
        service.addChangeListener { observed += service.state }
        val loaded = service.state.copy(themeId = " NORD ", fontSize = Int.MAX_VALUE, smartSuggestionsEnabled = true)

        service.loadState(loaded)
        assertEquals(1, observed.size)
        assertEquals("nord", observed.single().themeId)
        assertEquals(TerminalConfig.FONT_SIZE_MAX, observed.single().fontSize)
        assertTrue(observed.single().smartSuggestionsEnabled)
        assertEquals(0L, service.stateModificationCount)

        service.replaceState(service.state.copy(smartSuggestionsEnabled = false))
        assertEquals(2, observed.size)
        assertFalse(observed.last().smartSuggestionsEnabled)
        assertEquals(1L, service.stateModificationCount)
    }

    @Test
    fun `equivalent loaded and applied states do not notify or mark settings modified`() {
        val service = KetraTermIntellijSettings()
        service.loadState(service.state.copy(themeId = "nord"))
        var changes = 0
        service.addChangeListener { changes++ }

        service.loadState(service.state.copy(themeId = " NORD "))
        service.replaceState(service.state.copy(themeId = "NORD"))

        assertEquals(0, changes)
        assertEquals(0L, service.stateModificationCount)
    }

    @Test
    fun `listener failure does not prevent later consumers from observing committed state`() {
        val service = KetraTermIntellijSettings()
        val firstFailure = IllegalStateException("pane refresh failed")
        val secondFailure = IllegalArgumentException("policy refresh failed")
        var observed = false
        service.addChangeListener { throw firstFailure }
        service.addChangeListener { throw firstFailure }
        service.addChangeListener { throw secondFailure }
        service.addChangeListener { observed = service.state.smartSuggestionsEnabled }

        val actual =
            assertThrows(IllegalStateException::class.java) {
                service.loadState(service.state.copy(smartSuggestionsEnabled = true))
            }

        assertSame(firstFailure, actual)
        assertEquals(listOf(secondFailure), actual.suppressed.toList())
        assertTrue(observed)
        assertTrue(service.state.smartSuggestionsEnabled)
    }

    @Test
    fun `listener cancellation remains visible after other consumers are notified`() {
        for (cancellation in listOf(CancellationException("settings caller cancelled"), ProcessCanceledException())) {
            val service = KetraTermIntellijSettings()
            val failure = IllegalStateException("pane refresh failed")
            var observed = false
            service.addChangeListener { throw failure }
            service.addChangeListener { throw cancellation }
            service.addChangeListener { throw cancellation }
            service.addChangeListener { observed = service.state.smartSuggestionsEnabled }

            val actual =
                assertThrows(CancellationException::class.java) {
                    service.replaceState(service.state.copy(smartSuggestionsEnabled = true))
                }

            assertSame(cancellation, actual)
            assertEquals(listOf(failure), actual.suppressed.toList())
            assertTrue(observed)
        }
    }

    @Test
    fun `smart suggestions default off and map independently from automatic popup`() {
        val defaults = KetraTermIntellijSettings.State(themeId = "nord")
        assertFalse(defaults.smartSuggestionsEnabled)
        assertTrue(defaults.shellSuggestionsEnabled)
        val mapped =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                defaults.copy(smartSuggestionsEnabled = true, shellSuggestionsEnabled = false),
            )
        assertTrue(mapped.smartSuggestionsEnabled)
        assertFalse(mapped.shellSuggestionsEnabled)
        assertFalse(KetraTermIntellijSettingsMapper.toSwingSettings(defaults).smartSuggestionsEnabled)
    }

    @Test
    fun `default theme id follows IntelliJ`() {
        val state = KetraTermIntellijSettings.State()

        assertEquals(KetraTermIntellijSettings.DEFAULT_THEME_ID, state.themeId)
    }

    @Test
    fun `default font size matches IntelliJ terminal default`() {
        val settings =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord"),
            )

        assertEquals(KetraTermIntellijSettings.DEFAULT_FONT_SIZE, settings.font.size)
    }

    @Test
    fun `default terminal grid keeps top edge open with horizontal gutter and bottom spacer`() {
        val settings =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord"),
            )

        assertEquals(SwingPadding(0, 4, 4, 6), settings.padding)
        assertEquals(SwingPadding(0, 2, 2, 2), settings.alternateScreenPadding)
        assertEquals(16, settings.shellIntegrationDecorationGutterWidth)
    }

    @Test
    fun `visual bell defaults on and maps to swing settings`() {
        val enabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord"),
            )
        val disabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord", visualBell = false),
            )

        assertEquals(true, enabled.visualBellEnabled)
        assertFalse(disabled.visualBellEnabled)
    }

    @Test
    fun `shell suggestions setting maps to swing settings`() {
        val enabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord"),
            )
        val disabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord", shellSuggestionsEnabled = false),
            )

        assertTrue(enabled.shellSuggestionsEnabled)
        assertFalse(disabled.shellSuggestionsEnabled)
    }

    @Test
    fun `enter suggestion acceptance defaults on and maps to swing settings`() {
        val enabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord"),
            )
        val disabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(
                    themeId = "nord",
                    acceptSelectedSuggestionWithEnter = false,
                ),
            )

        assertTrue(enabled.acceptSelectedSuggestionWithEnter)
        assertFalse(disabled.acceptSelectedSuggestionWithEnter)
    }

    @Test
    fun `completion learning persistence is privacy preserving by default`() {
        val state = KetraTermIntellijSettings.State()

        assertFalse(state.completionLearningPersistenceEnabled)
    }

    @Test
    fun `scroll on output setting maps to swing settings`() {
        val enabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord"),
            )
        val disabled =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord", scrollOnOutput = false),
            )

        assertTrue(enabled.scrollOnOutput)
        assertFalse(disabled.scrollOnOutput)
    }

    @Test
    fun `normalizes unknown theme ids to IntelliJ native theme`() {
        assertEquals(
            KetraTermIntellijSettings.DEFAULT_THEME_ID,
            KetraTermIntellijSettings.normalizeThemeId("missing-theme"),
        )
    }

    @Test
    fun `maps built in theme ids to built in palettes`() {
        val settings =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "one-dark"),
            )
        val expected = TerminalTheme.ONE_DARK.createPalette()

        assertEquals(expected.defaultForeground, settings.palette.defaultForeground)
        assertEquals(expected.defaultBackground, settings.palette.defaultBackground)
        assertEquals(expected.indexedColor(1), settings.palette.indexedColor(1))
    }

    @Test
    fun `clamps hostile persisted dimensions before creating Swing settings`() {
        val service = KetraTermIntellijSettings()
        service.loadState(
            KetraTermIntellijSettings.State(
                themeId = "nord",
                columns = Int.MAX_VALUE,
                rows = Int.MIN_VALUE,
                fontSize = Int.MAX_VALUE,
                cursorBlinkMillis = -1,
                scrollbackLines = Int.MAX_VALUE,
                lineHeight = Float.NaN,
            ),
        )
        val settings = service.current()

        assertEquals(TerminalConfig.COLUMNS_MAX, settings.columns)
        assertEquals(TerminalConfig.ROWS_MIN, settings.rows)
        assertEquals(TerminalConfig.FONT_SIZE_MAX, settings.font.size)
        assertEquals(TerminalConfig.CURSOR_BLINK_MIN, settings.cursorBlinkMillis)
        assertEquals(TerminalConfig.SCROLLBACK_MAX, settings.scrollbackLines)
        assertEquals(TerminalConfig.DEFAULT_LINE_HEIGHT, settings.lineHeight)
        assertFalse(settings.shellRequestResizeWindow)
        assertFalse(settings.shellRequestWindowManipulation)
    }

    @Test
    fun `normalizer canonicalizes persisted ui state`() {
        val state =
            KetraTermIntellijSettingsNormalizer.normalize(
                KetraTermIntellijSettings.State(
                    themeId = "TOKYO-NIGHT",
                    fontFamily = "  ",
                    fallbackFontFamily = "  ",
                    fontSize = 1,
                    columns = 1,
                    rows = Int.MAX_VALUE,
                    cursorBlinkMillis = Int.MAX_VALUE,
                    cursorShape = "bar",
                    scrollbackLines = -1,
                    lineHeight = Float.POSITIVE_INFINITY,
                    shellPath = "  ",
                    environmentVariables = " ONE =first \nmissing\nTWO=second\nONE=last",
                    defaultTabName = "  ",
                ),
            )

        assertEquals("tokyo-night", state.themeId)
        assertEquals(KetraTermIntellijSettings.DEFAULT_FONT_FAMILY, state.fontFamily)
        assertEquals(KetraTermIntellijSettings.DEFAULT_FONT_FAMILY, state.fallbackFontFamily)
        assertEquals(TerminalConfig.FONT_SIZE_MIN, state.fontSize)
        assertEquals(TerminalConfig.COLUMNS_MIN, state.columns)
        assertEquals(TerminalConfig.ROWS_MAX, state.rows)
        assertEquals(TerminalConfig.CURSOR_BLINK_MAX, state.cursorBlinkMillis)
        assertEquals("beam", state.cursorShape)
        assertEquals(TerminalConfig.SCROLLBACK_MIN, state.scrollbackLines)
        assertEquals(TerminalConfig.DEFAULT_LINE_HEIGHT, state.lineHeight)
        assertEquals(TerminalConfig.DEFAULT_SHELL_PATH, state.shellPath)
        assertEquals("ONE=last\nTWO=second", state.environmentVariables)
        assertEquals("Local", state.defaultTabName)
    }

    @Test
    fun `normalizer uses field specific security defaults for invalid persisted values`() {
        val state =
            KetraTermIntellijSettingsNormalizer.normalize(
                KetraTermIntellijSettings.State(
                    clipboardWrite = "invalid",
                    clipboardRead = "invalid",
                    titlePermission = "invalid",
                ),
            )

        assertEquals(TerminalClipboardPermission.ALLOW.name.lowercase(), state.clipboardWrite)
        assertEquals(TerminalClipboardPermission.DENY.name.lowercase(), state.clipboardRead)
        assertEquals(TerminalTitlePermission.ALLOW.name.lowercase(), state.titlePermission)
    }

    @Test
    fun `host policy applies configured permissions to the whole session`() {
        val settings = KetraTermIntellijSettings()
        val defaults = settings.createHostPolicy()
        assertEquals(TerminalClipboardPermission.ALLOW, defaults.clipboardPolicy.writePermission)
        assertEquals(TerminalClipboardPermission.PROMPT, defaults.clipboardPolicy.readPermission)
        assertEquals(TerminalTitlePermission.ALLOW, defaults.titlePolicy.permission)

        settings.loadState(settings.state.copy(clipboardWrite = "deny", titlePermission = "deny"))
        val policy = settings.createHostPolicy()
        assertEquals(TerminalClipboardPermission.DENY, policy.clipboardPolicy.writePermission)
        assertEquals(TerminalTitlePermission.DENY, policy.titlePolicy.permission)
    }

    @Test
    fun `parses environment variables without accepting malformed entries`() {
        val environment =
            KetraTermIntellijSettingsNormalizer.parseEnvironmentVariables(
                " JVM_OPTS =-Xmx1g\nNO_EQUALS\n=missing\nEMPTY=\nPATH=C:\\Tools=StillValue",
            )

        val expected =
            mapOf(
                "JVM_OPTS" to "-Xmx1g",
                "EMPTY" to "",
                "PATH" to "C:\\Tools=StillValue",
            )
        assertEquals(expected, environment)
    }

    @Test
    fun `maps cursor shape ids`() {
        val settings =
            KetraTermIntellijSettingsMapper.toSwingSettings(
                KetraTermIntellijSettings.State(themeId = "nord", cursorShape = "beam"),
            )

        assertEquals(TerminalRenderCursorShape.BAR, settings.cursorShape)
    }

    @Test
    fun `native palette uses editor foreground background selection and cursor colors`() {
        val palette =
            KetraTermIntellijThemePalette.fromSource(
                KetraTermIntellijThemePalette.ColorSource(
                    foreground = 0xFF202124.toInt(),
                    background = 0xFFFAFAFA.toInt(),
                    selectionForeground = 0xFFFFFFFF.toInt(),
                    selectionBackground = 0xFF3366CC.toInt(),
                    cursor = 0xFF112233.toInt(),
                ),
            )

        assertEquals(0xFF202124.toInt(), palette.defaultForeground)
        assertFalse(palette.isDark)
        assertEquals(0xFFFAFAFA.toInt(), palette.defaultBackground)
        assertEquals(0xFFFFFFFF.toInt(), palette.selectionForeground)
        assertEquals(0xFF3366CC.toInt(), palette.selectionBackground)
        assertEquals(0xFF112233.toInt(), palette.cursorBackground)
        assertEquals(0xFFFAFAFA.toInt(), palette.indexedColor(0))
        assertEquals(0xFF202124.toInt(), palette.indexedColor(7))
        assertNotEquals(palette.cursorBackground, palette.cursorForeground)
    }

    @Test
    fun `native dark palette preserves host theme preference`() {
        val palette =
            KetraTermIntellijThemePalette.fromSource(
                KetraTermIntellijThemePalette.ColorSource(
                    foreground = 0xffeeeeee.toInt(),
                    background = 0xff202124.toInt(),
                    selectionForeground = null,
                    selectionBackground = null,
                    cursor = null,
                ),
            )
        assertTrue(palette.isDark)
    }
}
