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
package io.github.ketraterm.intellij.services

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.*
import com.intellij.util.ui.update.UiNotifyConnector
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.settings.KetraTermProjectSettings
import io.github.ketraterm.intellij.ui.KetraTermTerminalPane
import io.github.ketraterm.intellij.ui.KetraTermTerminalPaneHostActions
import io.github.ketraterm.intellij.ui.KetraTermTerminalStartupView
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.workspace.*
import java.awt.BorderLayout
import java.awt.Component
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Project-level owner for IntelliJ-hosted KetraTerm tabs and sessions.
 *
 * The service adapts IntelliJ `Content` tabs to the host-neutral
 * [TerminalWorkspace]. Closing an IDE tab disposes the corresponding pane and
 * terminal session. Project closing freezes restart metadata before disposing
 * sessions; [KetraTermTerminalTabsStorage] owns XML serialization independently
 * of the UI lifecycle.
 *
 * @property project IntelliJ project that owns this terminal workspace.
 */
@Service(Service.Level.PROJECT)
class KetraTermProjectTerminalService internal constructor(
    private val project: Project,
    private val startWorkspaceTab: (TerminalWorkspace, TerminalProfile, TerminalWorkspaceOpenOptions) -> TerminalWorkspaceTab,
) : Disposable {
    /** Creates the project owner using IntelliJ's bundled PTY runtime. */
    constructor(project: Project) : this(project, IntelliJPtyRuntime()::openWorkspaceTab)

    private val contentsByTabId = LinkedHashMap<String, Content>()
    private val pendingTabsById = LinkedHashMap<String, PendingTerminalTab>()
    private val panesByTabId = LinkedHashMap<String, KetraTermTerminalPane>()
    private val closeListenersByTabId = LinkedHashMap<String, ContentCloseQueryRegistration>()
    private val workspace = TerminalWorkspace(IntellijWorkspaceListener())
    private val workspaceLock = Any()
    private val nextPendingTabNumber = AtomicInteger(1)
    private val settingsChangedListener = { reloadOpenTerminalSettings() }
    private var lastToolWindow: ToolWindow? = null
    private var tabsInitialized = false
    private var initializingTabs = false
    private var persistence: TerminalTabsPersistence? = null

    @Volatile
    private var disposed = false

    @Volatile
    private var closing = false

    init {
        KetraTermIntellijSettings.getInstance().addChangeListener(settingsChangedListener)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener {
                if (KetraTermIntellijSettings.getInstance().state.themeId == KetraTermIntellijSettings.DEFAULT_THEME_ID) {
                    reloadOpenTerminalSettings()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ProjectCloseListener.TOPIC,
            object : ProjectCloseListener {
                override fun projectClosingBeforeSave(project: Project) {
                    if (project === this@KetraTermProjectTerminalService.project) persistence?.capture()
                }

                override fun projectClosing(project: Project) {
                    if (project !== this@KetraTermProjectTerminalService.project) return
                    closing = true
                    persistence?.dispose()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TrustedProjectsListener.TOPIC,
            object : TrustedProjectsListener {
                override fun onProjectTrusted(project: Project) {
                    if (project !== this@KetraTermProjectTerminalService.project) return
                    invokeLaterIfAlive { lastToolWindow?.let(::ensureInitialTab) }
                }
            },
        )
    }

    /**
     * Returns true when this project already has an open terminal tab.
     */
    fun hasOpenTabs(): Boolean = contentsByTabId.isNotEmpty() || pendingTabsById.isNotEmpty()

    /**
     * Finds the terminal pane that owns [component].
     *
     * Registered IntelliJ actions use this to scope global keymap shortcuts to
     * the focused KetraTerm terminal pane without installing per-pane action
     * instances.
     *
     * @param component focused Swing component from the action event context.
     * @return owning terminal pane, or `null` when focus is outside KetraTerm.
     */
    internal fun paneForComponent(component: Component?): KetraTermTerminalPane? {
        if (component == null || disposed) return null
        for (pane in panesByTabId.values) {
            if (component === pane.component || SwingUtilities.isDescendingFrom(component, pane.component)) {
                return pane
            }
        }
        return null
    }

    /**
     * Restores saved tabs once, or opens a default tab when the tool window is empty.
     *
     * Automatic restoration waits for project trust. Restored tabs retain their
     * selection without requesting focus and start sessions only when shown.
     *
     * @param toolWindow target IntelliJ tool window.
     */
    fun ensureInitialTab(toolWindow: ToolWindow) {
        lastToolWindow = toolWindow
        if (disposed || closing || initializingTabs || !TrustedProjects.isProjectTrusted(project)) return
        initializeTabs(toolWindow)
        if (hasOpenTabs()) return
        openDefaultTab(toolWindow)
    }

    private fun initializeTabs(toolWindow: ToolWindow) {
        if (tabsInitialized || initializingTabs || !TrustedProjects.isProjectTrusted(project)) return
        initializingTabs = true
        try {
            val storage = project.service<KetraTermTerminalTabsStorage>()
            val saved = storage.state
            // Content-manager access can initialize the factory reentrantly.
            val manager = toolWindow.contentManager
            val restored = saved.tabs.map { state -> openTab(toolWindow, null, state) }
            restored.getOrNull(saved.selectedTabIndex)?.let { manager.setSelectedContent(it, false) }
            persistence =
                TerminalTabsPersistence(storage, manager, this) { content ->
                    content.getUserData(TAB_STATE)?.invoke()
                }
            tabsInitialized = true
            persistence?.capture()
            // Arm lazy startup only after restoring selection; adding the first content can temporarily select it.
            for ((id, pending) in pendingTabsById) {
                if (pending.restoredState != null) {
                    UiNotifyConnector.doWhenFirstShown(pending.container) { startPendingTab(id) }
                }
            }
        } finally {
            initializingTabs = false
        }
    }

    /**
     * Opens one local terminal tab in [toolWindow].
     *
     * @param toolWindow target IntelliJ tool window.
     * @param workingDirectory explicit local directory, overriding the configured start directory when supplied.
     * @return created content tab containing either a pending, running, or failure state.
     */
    fun openDefaultTab(
        toolWindow: ToolWindow,
        workingDirectory: Path? = null,
    ): Content {
        check(!disposed && !closing) { "KetraTerm project terminal service is closing or disposed" }

        lastToolWindow = toolWindow
        initializeTabs(toolWindow)
        val settingsService = KetraTermIntellijSettings.getInstance()
        val settingsState = settingsService.state
        val profile = KetraTermDefaultProfileFactory.defaultProfile(project, settingsState, workingDirectory)
        return openTab(toolWindow, profile)
    }

    /**
     * Opens one local terminal tab for an explicitly selected profile.
     *
     * @param toolWindow target IntelliJ tool window.
     * @param profile selected shell profile discovered by the shared profile registry.
     * @return created content tab.
     */
    fun openProfileTab(
        toolWindow: ToolWindow,
        profile: TerminalProfile,
    ): Content {
        check(!disposed && !closing) { "KetraTerm project terminal service is closing or disposed" }

        lastToolWindow = toolWindow
        initializeTabs(toolWindow)
        val settingsState = KetraTermIntellijSettings.getInstance().state
        val configuredProfile =
            KetraTermDefaultProfileFactory.profileForSelectedShell(project, profile, settingsState)
        return openTab(toolWindow, configuredProfile)
    }

    private fun openTab(
        toolWindow: ToolWindow,
        profile: TerminalProfile?,
        restoredState: TerminalTabState? = null,
    ): Content {
        val displayName =
            restoredState?.customTitle ?: profile?.displayName
                ?: KetraTermIntellijSettings.getInstance().state.defaultTabName
        val pendingId = "pending-terminal-${nextPendingTabNumber.getAndIncrement()}"
        val container =
            JPanel(BorderLayout()).apply {
                border = null
                add(KetraTermTerminalStartupView.starting(displayName), BorderLayout.CENTER)
            }
        val content =
            ContentFactory.getInstance().createContent(
                container,
                displayName,
                false,
            )

        content.isCloseable = true
        @Suppress("UsePropertyAccessSyntax")
        content.setDisposer(PendingTerminalTabDisposable(pendingId))

        val pending = PendingTerminalTab(content, container, profile, restoredState)
        pendingTabsById[pendingId] = pending
        content.putUserData(TAB_STATE) {
            restoredState ?: TerminalTabRestore.snapshot(requireNotNull(profile), null, null)
        }

        val contentManager = toolWindow.contentManager
        contentManager.addContent(content)
        if (restoredState == null) {
            contentManager.setSelectedContent(content, true)
            startPendingTab(pendingId)
        }
        return content
    }

    private fun startPendingTab(pendingId: String) {
        val pending = pendingTabsById[pendingId] ?: return
        if (disposed || closing || pending.startRequested) return
        pending.startRequested = true
        val settingsService = KetraTermIntellijSettings.getInstance()
        val settingsState = settingsService.state
        val settings = settingsService.current()
        val basePath = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || closing || pending.closed) return@executeOnPooledThread
            val result =
                try {
                    val profile =
                        pending.sourceProfile
                            ?: TerminalTabRestore.profile(basePath, requireNotNull(pending.restoredState), settingsState)
                    val launchProfile =
                        profile
                            .withProjectSdkEnvironment(project, enabled = settingsState.addProjectJdkToPath)
                            .copy(
                                startupCommand =
                                    profile.startupCommand ?: TerminalStartupCommand.fromText(
                                        project.service<KetraTermProjectSettings>().state.startupCommand,
                                    ),
                            )
                    val tab =
                        synchronized(workspaceLock) {
                            if (disposed || closing || pending.closed) return@executeOnPooledThread
                            startWorkspaceTab(workspace, launchProfile, openOptions(settings, profile))
                        }
                    TerminalStartupResult.Started(tab, profile)
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    TerminalStartupResult.Failed(exception)
                } catch (error: LinkageError) {
                    TerminalStartupResult.Failed(error)
                }
            invokeLaterIfAlive {
                publishTerminalStartupResult(pendingId, result)
            }
        }
    }

    override fun dispose() {
        if (disposed) return
        persistence?.dispose()
        disposed = true

        val panes = panesByTabId.values.toList()
        val closeRegistrations = closeListenersByTabId.values.toList()
        for (registration in closeRegistrations) {
            registration.manager.removeContentManagerListener(registration.listener)
        }

        panesByTabId.clear()
        contentsByTabId.clear()
        pendingTabsById.clear()
        closeListenersByTabId.clear()

        for (pane in panes) {
            pane.close()
        }
        KetraTermIntellijSettings.getInstance().removeChangeListener(settingsChangedListener)
        synchronized(workspaceLock) {
            workspace.close()
        }
    }

    private fun closeTabFromContent(tabId: String) {
        if (disposed) return

        val pane = panesByTabId.remove(tabId)
        val content = contentsByTabId.remove(tabId)
        removeCloseQueryListener(tabId)
        if (pane == null && content == null) return

        pane?.close()
        synchronized(workspaceLock) {
            workspace.closeTab(tabId)
        }
    }

    private fun closeTabAfterRemoteSessionExit(tab: TerminalWorkspaceTab) {
        if (disposed) return

        val pane = panesByTabId.remove(tab.id) ?: return
        val content = contentsByTabId.remove(tab.id)
        removeCloseQueryListener(tab.id)

        pane.close()
        synchronized(workspaceLock) {
            workspace.closeTab(tab.id)
        }

        content?.manager?.removeContent(content, true)

        if (!closing && !hasOpenTabs()) {
            lastToolWindow?.let(::openDefaultTab)
        }
    }

    private fun closePendingTab(pendingId: String) {
        pendingTabsById.remove(pendingId)?.closed = true
    }

    private fun publishTerminalStartupResult(
        pendingId: String,
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
                bindStartedTab(pendingTab, result.tab, result.sourceProfile)
            }
            is TerminalStartupResult.Failed -> showStartupFailure(pendingTab, result.error)
        }
    }

    private fun bindStartedTab(
        pendingTab: PendingTerminalTab,
        workspaceTab: TerminalWorkspaceTab,
        sourceProfile: TerminalProfile,
    ) {
        workspaceTab.customTitle = pendingTab.restoredState?.customTitle
        val pane =
            KetraTermTerminalPane.create(
                project = project,
                tab = workspaceTab,
                hostActions =
                    KetraTermTerminalPaneHostActions(
                        openNewTabAction = ::openDefaultTabFromContextMenu,
                        canOpenTerminalHereAction = ::canOpenTerminalHere,
                        openTerminalHereAction = { tab -> openTerminalHere(tab, sourceProfile) },
                        closePaneAction = ::closePaneFromContextMenu,
                    ),
            )
        replaceContent(pendingTab.container, pane.component)
        pendingTab.content.displayName = workspaceTab.title
        pendingTab.content.preferredFocusableComponent = pane.terminal
        @Suppress("UsePropertyAccessSyntax")
        pendingTab.content.setDisposer(TerminalTabDisposable(workspaceTab.id))
        installCloseQueryListener(pendingTab.content, workspaceTab)
        contentsByTabId[workspaceTab.id] = pendingTab.content
        panesByTabId[workspaceTab.id] = pane
        pendingTab.content.putUserData(TAB_STATE) {
            TerminalTabRestore.snapshot(sourceProfile, workspaceTab.customTitle, workspaceTab.currentWorkingDirectoryUri)
        }
        persistence?.capture()
        if (pendingTab.content.isSelected && lastToolWindow?.isActive == true) pane.requestFocus()
        val sessionState = workspaceTab.session.state.value
        if (sessionState is TerminalSessionState.Closed && !sessionState.event.locallyRequested) {
            closeTabAfterRemoteSessionExit(workspaceTab)
        }
    }

    private fun showStartupFailure(
        pendingTab: PendingTerminalTab,
        error: Throwable,
    ) {
        val profileName = pendingTab.sourceProfile?.displayName ?: KetraTermIntellijSettings.getInstance().state.defaultTabName
        pendingTab.content.displayName = pendingTab.restoredState?.customTitle ?: "Failed: $profileName"
        replaceContent(
            pendingTab.container,
            KetraTermTerminalStartupView.failure(profileName, error),
        )
    }

    private fun replaceContent(
        container: JPanel,
        component: Component,
    ) {
        container.removeAll()
        container.add(component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
    }

    private fun openDefaultTabFromContextMenu(): Boolean {
        val toolWindow = lastToolWindow ?: return false
        openDefaultTab(toolWindow)
        return true
    }

    private fun canOpenTerminalHere(tab: TerminalWorkspaceTab): Boolean =
        lastToolWindow != null && IntellijWorkingDirectoryResolver.resolve(tab.currentWorkingDirectoryUri) != null

    private fun openTerminalHere(
        tab: TerminalWorkspaceTab,
        sourceProfile: TerminalProfile,
    ): Boolean {
        val toolWindow = lastToolWindow ?: return false
        val workingDirectory = IntellijWorkingDirectoryResolver.resolve(tab.currentWorkingDirectoryUri) ?: return false
        openTab(
            toolWindow = toolWindow,
            profile = sourceProfile.copy(workingDirectory = workingDirectory),
        )
        return true
    }

    private fun closePaneFromContextMenu(tab: TerminalWorkspaceTab) {
        val content = contentsByTabId[tab.id] ?: return
        content.manager?.removeContent(content, true)
    }

    private fun reloadOpenTerminalSettings() {
        invokeLaterIfAlive {
            for (tab in workspace.tabSnapshot()) {
                tab.showForegroundProcessName = KetraTermIntellijSettings.getInstance().state.showForegroundProcessName
            }
            for (pane in panesByTabId.values) {
                pane.reloadSettings()
            }
        }
    }

    private fun openOptions(
        settings: SwingSettings,
        profile: TerminalProfile,
    ): TerminalWorkspaceOpenOptions =
        TerminalWorkspaceOpenOptions(
            columns = settings.columns,
            rows = settings.rows,
            treatAmbiguousAsWide = settings.treatAmbiguousAsWide,
            maxHistory = settings.scrollbackLines,
            pasteControlPolicy = settings.pasteControlPolicy,
            hostPolicy = KetraTermIntellijSettings.getInstance().createHostPolicy(),
            showForegroundProcessName = KetraTermIntellijSettings.getInstance().state.showForegroundProcessName,
        )

    private fun installCloseQueryListener(
        content: Content,
        tab: TerminalWorkspaceTab,
    ) {
        val listener =
            object : ContentManagerListener {
                override fun contentRemoveQuery(event: ContentManagerEvent) {
                    if (event.content !== content) return
                    if (!confirmLiveProcessClose(tab)) {
                        event.consume()
                    }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    if (event.content !== content) return
                    removeCloseQueryListener(tab.id)
                }
            }
        val manager = content.manager ?: return
        manager.addContentManagerListener(listener)
        closeListenersByTabId[tab.id] = ContentCloseQueryRegistration(manager, listener)
    }

    private fun removeCloseQueryListener(tabId: String) {
        val registration = closeListenersByTabId.remove(tabId) ?: return
        registration.manager.removeContentManagerListener(registration.listener)
    }

    private fun confirmLiveProcessClose(tab: TerminalWorkspaceTab): Boolean {
        if (!tab.session.shellIntegrationState.hasRunningCommand()) return true
        val answer =
            Messages.showYesNoDialog(
                project,
                "Closing \"${tab.title}\" will terminate its running process.",
                "Terminate Terminal Process?",
                "Terminate",
                "Cancel",
                Messages.getWarningIcon(),
            )
        return answer == Messages.YES
    }

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
        override fun shellIntegrationMarker(
            tab: TerminalWorkspaceTab,
            event: ShellIntegrationEvent,
        ) {
            if (!KetraTermIntellijSettings.getInstance().state.smartSuggestionsEnabled ||
                event.marker != ShellIntegrationMarker.COMMAND_FINISHED
            ) {
                return
            }
            val state = tab.session.shellIntegrationState
            val metadata = state.commandMetadata(state.latestCommandRecordId()) ?: return
            KetraTermCompletionService.getInstanceIfCreated()?.recordFinishedCommand(tab, metadata)
        }

        override fun bell(tab: TerminalWorkspaceTab) {
            invokeLaterIfAlive {
                panesByTabId[tab.id]?.terminal?.showVisualBell()
            }
        }

        override fun titleChanged(
            tab: TerminalWorkspaceTab,
            title: String,
        ) {
            invokeLaterIfAlive {
                contentsByTabId[tab.id]?.displayName = tab.title
                persistence?.capture()
            }
        }

        override fun currentWorkingDirectoryChanged(
            tab: TerminalWorkspaceTab,
            uri: String,
        ) {
            invokeLaterIfAlive { persistence?.capture() }
        }

        override fun startupCommandCancelled(tab: TerminalWorkspaceTab) {
            invokeLaterIfAlive {
                KetraTermIntellijNotifier.showNotification(
                    project,
                    "Startup command skipped",
                    "You entered input before the shell was ready. The startup command was not run.",
                    NotificationLevel.INFO,
                )
            }
        }

        override fun showNotification(
            tab: TerminalWorkspaceTab,
            title: String,
            body: String,
            level: NotificationLevel,
        ) {
            invokeLaterIfAlive {
                KetraTermIntellijNotifier.showNotification(project, title, body, level)
            }
        }

        override fun terminalClipboardWrite(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardWriteEvent,
        ) {
            if (!IntellijOsc52ClipboardSelections.targetsIdeClipboard(event.selection)) return
            invokeLaterIfAlive {
                panesByTabId[tab.id]?.terminal?.copyTextToClipboard(event.text)
            }
        }

        override fun terminalClipboardPrompt(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardPromptEvent,
        ) {
            if (!IntellijOsc52ClipboardSelections.targetsIdeClipboard(event.selection)) return
            invokeLaterIfAlive {
                val pane = panesByTabId[tab.id] ?: return@invokeLaterIfAlive
                val answer =
                    Messages.showYesNoDialog(
                        project,
                        IntellijOsc52ClipboardPromptText.message(tab.profile.displayName, event),
                        IntellijOsc52ClipboardPromptText.title(),
                        Messages.getWarningIcon(),
                    )
                if (answer == Messages.YES) {
                    pane.terminal.copyTextToClipboard(event.text)
                }
            }
        }

        override fun tabClosed(tabId: String) {
            invokeLaterIfAlive {
                contentsByTabId.remove(tabId)
                panesByTabId.remove(tabId)
                removeCloseQueryListener(tabId)
            }
        }

        override fun sessionClosed(
            tab: TerminalWorkspaceTab,
            exitCode: Int?,
            failure: Throwable?,
        ) {
            invokeLaterIfAlive {
                closeTabAfterRemoteSessionExit(tab)
            }
        }
    }

    private fun invokeLaterIfAlive(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !closing) {
                action()
            }
        }
    }

    companion object {
        private val TAB_STATE = Key.create<() -> TerminalTabState>("KetraTerm.tabState")

        /**
         * Returns the terminal service for [project].
         *
         * @param project IntelliJ project.
         * @return project terminal service.
         */
        fun getInstance(project: Project): KetraTermProjectTerminalService = project.service()
    }

    private class PendingTerminalTab(
        val content: Content,
        val container: JPanel,
        val sourceProfile: TerminalProfile?,
        val restoredState: TerminalTabState?,
    ) {
        var startRequested = false

        @Volatile
        var closed = false
    }

    private data class ContentCloseQueryRegistration(
        val manager: ContentManager,
        val listener: ContentManagerListener,
    )

    private sealed interface TerminalStartupResult {
        data class Started(
            val tab: TerminalWorkspaceTab,
            val sourceProfile: TerminalProfile,
        ) : TerminalStartupResult

        data class Failed(
            val error: Throwable,
        ) : TerminalStartupResult
    }
}

internal object IntellijOsc52ClipboardSelections {
    fun targetsIdeClipboard(selection: String): Boolean = selection.isEmpty() || selection.indexOf('c') >= 0
}

internal object IntellijOsc52ClipboardPromptText {
    fun title(): String = "Clipboard Access"

    fun message(
        profileName: String,
        event: TerminalClipboardPromptEvent,
    ): String {
        val terminalName = profileName.trim().ifBlank { "this terminal" }
        if (event.text.isEmpty()) {
            return "Allow an application in $terminalName to clear the IDE clipboard?"
        }
        val count = event.text.codePointCount(0, event.text.length)
        return "Allow an application in $terminalName to write ${count.formatCount("character")} to the IDE clipboard?"
    }

    private fun Int.formatCount(unit: String): String =
        if (this == 1) {
            "1 $unit"
        } else {
            "$this ${unit}s"
        }
}
