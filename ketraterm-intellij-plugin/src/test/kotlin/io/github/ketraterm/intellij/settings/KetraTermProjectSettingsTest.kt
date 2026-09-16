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

import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Component
import java.awt.Container

class KetraTermProjectSettingsTest : BasePlatformTestCase() {
    fun testStartupCommandAppliesResetsAndRoundTripsWithoutChangingOtherProject() {
        val settings = project.service<KetraTermProjectSettings>()
        val otherSettings = ProjectManager.getInstance().defaultProject.service<KetraTermProjectSettings>()
        val original = settings.state
        val otherOriginal = otherSettings.state
        val configurable = KetraTermProjectSettingsConfigurable(project)
        try {
            val component = configurable.createComponent()
            val field = descendants(component).filterIsInstance<JBTextField>().single()
            assertFalse(configurable.isModified)
            field.text = "  echo 'ready #1'  "
            assertTrue(configurable.isModified)
            configurable.apply()
            assertEquals("  echo 'ready #1'  ", settings.state.startupCommand)
            assertFalse(configurable.isModified)
            assertEquals(otherOriginal, otherSettings.state)
            assertNotSame(settings, otherSettings)

            val reloaded = KetraTermProjectSettings()
            reloaded.loadState(
                XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), KetraTermProjectSettings.State::class.java),
            )
            assertEquals(settings.state, reloaded.state)

            field.text = "changed"
            configurable.reset()
            assertEquals(settings.state.startupCommand, field.text)
            field.text = "echo\u001bunsafe"
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals("  echo 'ready #1'  ", settings.state.startupCommand)
            field.text = ""
            configurable.apply()
            assertEquals("", settings.state.startupCommand)
        } finally {
            configurable.disposeUIResources()
            settings.loadState(original)
        }
    }

    private fun descendants(component: Component): Sequence<Component> =
        sequence {
            yield(component)
            if (component is Container) for (child in component.components) yieldAll(descendants(child))
        }
}
