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
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.ketraterm.intellij.KetraTermBundle
import javax.swing.JComponent

/** Project settings page for the command executed in newly opened interactive terminals. */
class KetraTermProjectSettingsConfigurable(
    private val project: Project,
) : SearchableConfigurable {
    private var startupCommandField: JBTextField? = null
    private val settings: KetraTermProjectSettings get() = project.service()

    override fun getId(): String = "io.github.ketraterm.terminal.project.settings"

    override fun getDisplayName(): String = KetraTermBundle.message("settings.ketraterm.project.displayName")

    override fun createComponent(): JComponent {
        val field = JBTextField(settings.state.startupCommand)
        startupCommandField = field
        return panel {
            row(KetraTermBundle.message("settings.ketraterm.startupCommand")) {
                cell(field)
                    .align(AlignX.FILL)
                    .comment(KetraTermBundle.message("settings.ketraterm.startupCommand.comment"))
            }
        }
    }

    override fun isModified(): Boolean = startupCommandField?.text?.let { it != settings.state.startupCommand } ?: false

    override fun apply() {
        val text = startupCommandField?.text ?: return
        try {
            settings.replaceState(KetraTermProjectSettings.State(startupCommand = text))
        } catch (exception: IllegalArgumentException) {
            throw ConfigurationException(exception.message.orEmpty())
        }
    }

    override fun reset() {
        startupCommandField?.text = settings.state.startupCommand
    }

    override fun disposeUIResources() {
        startupCommandField = null
    }
}
