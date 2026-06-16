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
package io.github.jvterm.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowEx
import io.github.jvterm.intellij.JvTermBundle
import io.github.jvterm.intellij.services.JvTermProjectTerminalService
import io.github.jvterm.intellij.settings.JvTermIntellijProfileIcons
import io.github.jvterm.intellij.settings.JvTermSettingsConfigurable
import io.github.jvterm.workspace.TerminalProfile
import io.github.jvterm.workspace.TerminalProfileRegistry

/**
 * Creates the IntelliJ tool window that hosts JvTerm terminal tabs.
 */
class JvTermToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val terminalService = JvTermProjectTerminalService.getInstance(project)
        installTitleActions(project, toolWindow, terminalService)
        installEmptyToolWindowReopenListener(project, toolWindow, terminalService)
        terminalService.ensureInitialTab(toolWindow)
    }

    private fun installTitleActions(
        project: Project,
        toolWindow: ToolWindow,
        terminalService: JvTermProjectTerminalService,
    ) {
        val toolWindowEx = toolWindow as? ToolWindowEx ?: return
        toolWindowEx.setTabActions(
            NewTerminalAction(project, toolWindow, terminalService),
            Separator.create(),
            NewTerminalProfileGroup(project, toolWindow, terminalService),
        )
        toolWindow.setAdditionalGearActions(TerminalGearActionsGroup(project))
    }

    private fun installEmptyToolWindowReopenListener(
        project: Project,
        toolWindow: ToolWindow,
        terminalService: JvTermProjectTerminalService,
    ) {
        project.messageBus
            .connect(toolWindow.disposable)
            .subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun toolWindowShown(shownToolWindow: ToolWindow) {
                        if (shownToolWindow.id == toolWindow.id) {
                            terminalService.ensureInitialTab(shownToolWindow)
                        }
                    }
                },
            )
    }

    private class NewTerminalAction(
        private val project: Project,
        private val toolWindow: ToolWindow,
        private val terminalService: JvTermProjectTerminalService,
    ) : DumbAwareAction(
            JvTermBundle.message("action.jvterm.newTerminal.text"),
            JvTermBundle.message("action.jvterm.newTerminal.description"),
            AllIcons.General.Add,
        ) {
        override fun actionPerformed(event: AnActionEvent) {
            terminalService.openDefaultTab(toolWindow)
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !project.isDisposed
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class NewTerminalProfileGroup(
        private val project: Project,
        private val toolWindow: ToolWindow,
        private val terminalService: JvTermProjectTerminalService,
    ) : DefaultActionGroup(
            "",
            true,
        ) {
        private val profileIcons = JvTermIntellijProfileIcons()
        private val profileActions: Array<AnAction> =
            TerminalProfileRegistry()
                .availableProfiles()
                .map { profile ->
                    OpenTerminalProfileAction(
                        project = project,
                        toolWindow = toolWindow,
                        terminalService = terminalService,
                        profile = profile,
                        icon = profileIcons.icon(profile.kind),
                    )
                }.toTypedArray()

        init {
            templatePresentation.description = JvTermBundle.message("action.jvterm.newTerminalProfile.description")
            templatePresentation.icon = AllIcons.General.ButtonDropTriangle
        }

        override fun getChildren(event: AnActionEvent?): Array<AnAction> = profileActions

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class OpenTerminalProfileAction(
        private val project: Project,
        private val toolWindow: ToolWindow,
        private val terminalService: JvTermProjectTerminalService,
        private val profile: TerminalProfile,
        icon: javax.swing.Icon,
    ) : DumbAwareAction(
            profile.displayName,
            JvTermBundle.message("action.jvterm.openTerminalProfile.description", profile.displayName),
            icon,
        ) {
        override fun actionPerformed(event: AnActionEvent) {
            terminalService.openProfileTab(toolWindow, profile)
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !project.isDisposed
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class TerminalGearActionsGroup(
        project: Project,
    ) : DefaultActionGroup(
            listOf(OpenSettingsAction(project)),
        ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class OpenSettingsAction(
        private val project: Project,
    ) : DumbAwareAction(
            JvTermBundle.message("action.jvterm.settings.text"),
            JvTermBundle.message("action.jvterm.settings.description"),
            AllIcons.General.Settings,
        ) {
        override fun actionPerformed(event: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JvTermSettingsConfigurable::class.java)
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !project.isDisposed
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }
}
