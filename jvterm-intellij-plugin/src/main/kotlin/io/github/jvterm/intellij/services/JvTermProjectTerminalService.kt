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
package io.github.jvterm.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import io.github.jvterm.intellij.settings.JvTermIntellijSettings
import io.github.jvterm.intellij.ui.JvTermTerminalPane
import io.github.jvterm.intellij.ui.JvTermTerminalStartupView
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.workspace.TerminalWorkspace
import io.github.jvterm.workspace.TerminalWorkspaceListener
import io.github.jvterm.workspace.TerminalWorkspaceOpenOptions
import io.github.jvterm.workspace.TerminalWorkspaceTab
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

/**
 * Project-level owner for IntelliJ-hosted JvTerm tabs and sessions.
 *
 * The service adapts IntelliJ `Content` tabs to the host-neutral
 * [TerminalWorkspace]. Closing an IDE tab disposes the corresponding pane and
 * terminal session; closing the project disposes all remaining sessions.
 *
 * @property project IntelliJ project that owns this terminal workspace.
 */
@Service(Service.Level.PROJECT)
class JvTermProjectTerminalService(
    private val project: Project,
) : Disposable {
    private val contentsByTabId = LinkedHashMap<String, Content>()
    private val pendingTabsById = LinkedHashMap<String, PendingTerminalTab>()
    private val panesByTabId = LinkedHashMap<String, JvTermTerminalPane>()
    private val workspace = TerminalWorkspace(IntellijWorkspaceListener())
    private val workspaceLock = Any()
    private val nextPendingTabNumber = AtomicInteger(1)
    private val ptyRuntime = IntelliJPtyRuntime()
    private var disposed = false

    /**
     * Returns true when this project already has an open terminal tab.
     */
    fun hasOpenTabs(): Boolean = contentsByTabId.isNotEmpty() || pendingTabsById.isNotEmpty()

    /**
     * Opens the initial terminal tab if no terminal content exists yet.
     *
     * @param toolWindow target IntelliJ tool window.
     */
    fun ensureInitialTab(toolWindow: ToolWindow) {
        if (hasOpenTabs()) return
        openDefaultTab(toolWindow)
    }

    /**
     * Opens one local terminal tab in [toolWindow].
     *
     * @param toolWindow target IntelliJ tool window.
     * @return created content tab containing either a pending, running, or failure state.
     */
    fun openDefaultTab(toolWindow: ToolWindow): Content {
        check(!disposed) { "JvTerm project terminal service is disposed" }

        val profile = JvTermDefaultProfileFactory.defaultProfile(project)
        val settings = JvTermIntellijSettings.current()
        val pendingId = "pending-terminal-${nextPendingTabNumber.getAndIncrement()}"
        val container =
            JPanel(BorderLayout()).apply {
                border = null
                add(JvTermTerminalStartupView.starting(profile.displayName), BorderLayout.CENTER)
            }
        val contentManager = toolWindow.contentManager
        val content =
            contentManager.factory.createContent(
                container,
                profile.displayName,
                false,
            )

        content.isCloseable = true
        content.setDisposer(PendingTerminalTabDisposable(pendingId))

        pendingTabsById[pendingId] = PendingTerminalTab(content, container)

        contentManager.addContent(content)
        contentManager.setSelectedContent(content, true)

        startTerminalTabInBackground(
            pendingId = pendingId,
            profileName = profile.displayName,
            start = {
                synchronized(workspaceLock) {
                    ptyRuntime.openWorkspaceTab(
                        workspace = workspace,
                        profile = profile,
                        options = openOptions(settings),
                    )
                }
            },
        )
        return content
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        val panes = panesByTabId.values.toList()
        panesByTabId.clear()
        contentsByTabId.clear()
        pendingTabsById.clear()

        for (pane in panes) {
            pane.close()
        }
        synchronized(workspaceLock) {
            workspace.close()
        }
    }

    private fun closeTabFromContent(tabId: String) {
        if (disposed) return

        panesByTabId.remove(tabId)?.close()
        contentsByTabId.remove(tabId)
        synchronized(workspaceLock) {
            workspace.closeTab(tabId)
        }
    }

    private fun closePendingTab(pendingId: String) {
        pendingTabsById.remove(pendingId)
    }

    private fun startTerminalTabInBackground(
        pendingId: String,
        profileName: String,
        start: () -> TerminalWorkspaceTab,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result =
                try {
                    TerminalStartupResult.Started(start())
                } catch (exception: Exception) {
                    TerminalStartupResult.Failed(exception)
                } catch (error: LinkageError) {
                    TerminalStartupResult.Failed(error)
                }
            invokeLaterIfAlive {
                publishTerminalStartupResult(pendingId, profileName, result)
            }
        }
    }

    private fun publishTerminalStartupResult(
        pendingId: String,
        profileName: String,
        result: TerminalStartupResult,
    ) {
        val pendingTab = pendingTabsById[pendingId]
        if (pendingTab == null) {
            if (result is TerminalStartupResult.Started) {
                synchronized(workspaceLock) {
                    workspace.closeTab(result.tab.id)
                }
            }
            return
        }

        when (result) {
            is TerminalStartupResult.Started -> {
                pendingTabsById.remove(pendingId)
                bindStartedTab(pendingTab, result.tab)
            }
            is TerminalStartupResult.Failed -> showStartupFailure(pendingTab, profileName, result.error)
        }
    }

    private fun bindStartedTab(
        pendingTab: PendingTerminalTab,
        workspaceTab: TerminalWorkspaceTab,
    ) {
        val pane = JvTermTerminalPane.create(workspaceTab)
        replaceContent(pendingTab.container, pane.component)
        pendingTab.content.displayName = workspaceTab.title
        pendingTab.content.setPreferredFocusableComponent(pane.terminal)
        pendingTab.content.setDisposer(TerminalTabDisposable(workspaceTab.id))
        contentsByTabId[workspaceTab.id] = pendingTab.content
        panesByTabId[workspaceTab.id] = pane
        pane.requestFocus()
    }

    private fun showStartupFailure(
        pendingTab: PendingTerminalTab,
        profileName: String,
        error: Throwable,
    ) {
        pendingTab.content.displayName = "Failed: $profileName"
        replaceContent(
            pendingTab.container,
            JvTermTerminalStartupView.failure(profileName, error),
        )
    }

    private fun replaceContent(
        container: JPanel,
        component: java.awt.Component,
    ) {
        container.removeAll()
        container.add(component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun openOptions(settings: SwingSettings): TerminalWorkspaceOpenOptions =
        TerminalWorkspaceOpenOptions(
            columns = settings.columns,
            rows = settings.rows,
            treatAmbiguousAsWide = settings.treatAmbiguousAsWide,
            maxHistory = settings.scrollbackLines,
        )

    private inner class TerminalTabDisposable(
        private val tabId: String,
    ) : Disposable {
        override fun dispose() {
            closeTabFromContent(tabId)
        }
    }

    private inner class PendingTerminalTabDisposable(
        private val pendingId: String,
    ) : Disposable {
        override fun dispose() {
            closePendingTab(pendingId)
        }
    }

    private inner class IntellijWorkspaceListener : TerminalWorkspaceListener {
        override fun titleChanged(
            tab: TerminalWorkspaceTab,
            title: String,
        ) {
            invokeLaterIfAlive {
                contentsByTabId[tab.id]?.displayName = title
            }
        }

        override fun tabClosed(tabId: String) {
            invokeLaterIfAlive {
                contentsByTabId.remove(tabId)
                panesByTabId.remove(tabId)
            }
        }
    }

    private fun invokeLaterIfAlive(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                action()
            }
        }
    }

    companion object {
        /**
         * Returns the terminal service for [project].
         *
         * @param project IntelliJ project.
         * @return project terminal service.
         */
        fun getInstance(project: Project): JvTermProjectTerminalService = project.service()
    }

    private data class PendingTerminalTab(
        val content: Content,
        val container: JPanel,
    )

    private sealed interface TerminalStartupResult {
        data class Started(
            val tab: TerminalWorkspaceTab,
        ) : TerminalStartupResult

        data class Failed(
            val error: Throwable,
        ) : TerminalStartupResult
    }
}
