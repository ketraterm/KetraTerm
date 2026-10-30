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
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.*
import com.intellij.util.ui.update.UiNotifyConnector
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.settings.KetraTermProjectSettings
import io.github.ketraterm.intellij.ui.*
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.ui.swing.host.SwingClipboardPrompts
import io.github.ketraterm.ui.swing.host.SwingClipboardReader
import io.github.ketraterm.ui.swing.host.SwingDialogRequest
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.workspace.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
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
    workspaceFactory: (TerminalWorkspaceListener) -> TerminalWorkspace = ::TerminalWorkspace,
    private val commandAlertFocusOwner: () -> Component? = { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner },
    private val commandAlertNotifier: (String, String, NotificationLevel) -> Unit = { title, body, level ->
        KetraTermIntellijNotifier.showNotification(project, title, body, level)
    },
    private val prepareStartup: (TerminalProfile) -> Unit,
) : Disposable {
    /** Creates the project owner using IntelliJ's bundled PTY runtime. */
    constructor(project: Project) : this(project, prepareStartup = { IntelliJPtyRuntime.BundledPty4jRuntime.requireAvailable() })

    private val contentsByTabId = LinkedHashMap<String, Content>()
    private val pendingTabsById = LinkedHashMap<String, PendingTerminalTab>()
    private val panesByTabId = LinkedHashMap<String, KetraTermTerminalPane>()
    private val clipboardSessions = ConcurrentHashMap<String, IntellijClipboardSession>()
    private val pendingCommandAlerts = ConcurrentHashMap.newKeySet<String>()
    private val clipboardReader = SwingClipboardReader()

    // Only accessed during serialized workspace creation and its synchronous tabOpened callback.
    private var startingTab: PendingTerminalTab? = null
    private val closeListenersByTabId = LinkedHashMap<String, ContentCloseQueryRegistration>()
    private val workspace = workspaceFactory(IntellijWorkspaceListener())
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
            LafManagerListener.TOPIC,
            LafManagerListener { reloadOpenTerminalSettings() },
        )
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
                    for (binding in clipboardSessions.values) Disposer.dispose(binding)
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

        val client = project.service<IntellijClipboardClient>()
        val applicationClient = service<IntellijClipboardClient>()
        val pending = PendingTerminalTab(content, container, profile, restoredState, client, applicationClient)
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
        val startupCommand = project.service<KetraTermProjectSettings>().state.startupCommand
        val options = openOptions(settings)
        val accepted =
            workspace.openTabAsync(
                profile = {
                    check(!disposed && !closing && !pending.closed) { "Terminal startup cancelled" }
                    val profile =
                        pending.sourceProfile
                            ?: TerminalTabRestore.profile(basePath, requireNotNull(pending.restoredState), settingsState)
                    val launchProfile =
                        profile
                            .withProjectSdkEnvironment(project, enabled = settingsState.addProjectJdkToPath)
                            .copy(
                                startupCommand =
                                    profile.startupCommand ?: TerminalStartupCommand.fromText(startupCommand),
                            )
                    prepareStartup(launchProfile)
                    pending.preparedProfile = profile
                    startingTab = pending
                    launchProfile
                },
                options = options,
            ) { outcome ->
                startingTab = null
                val result =
                    outcome.fold(
                        onSuccess = { TerminalStartupResult.Started(it) },
                        onFailure = { TerminalStartupResult.Failed(it) },
                    )
                invokeLaterIfAlive {
                    publishTerminalStartupResult(pendingId, result)
                }
            }
        if (!accepted) showStartupFailure(pending, IllegalStateException("Terminal startup queue is full or shutting down"))
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pendingCommandAlerts.clear()

        val panes = panesByTabId.values.toList()
        val closeRegistrations = closeListenersByTabId.values.toList()
        for (pending in pendingTabsById.values) {
            pending.closed = true
            pending.startedTab?.session?.requestClose()
        }
        panesByTabId.clear()
        contentsByTabId.clear()
        pendingTabsById.clear()
        closeListenersByTabId.clear()

        var failure: Throwable? = null
        failure = captureCleanupFailure(failure) { persistence?.dispose() }
        for ((manager, listener) in closeRegistrations) {
            failure =
                captureCleanupFailure(failure) {
                    manager.removeContentManagerListener(listener)
                }
        }
        failure = captureCleanupFailure(failure) { KetraTermIntellijSettings.getInstance().removeChangeListener(settingsChangedListener) }
        for (tab in workspace.tabSnapshot()) tab.session.requestClose()
        workspace.closeAsync(::reportWorkspaceCleanup)
        for (pane in panes) {
            failure = captureCleanupFailure(failure, pane::close)
        }
        failure?.let { throw it }
    }

    private fun closeTabFromContent(tabId: String) {
        if (disposed) return

        val pane = panesByTabId.remove(tabId)
        val content = contentsByTabId.remove(tabId)
        var failure = captureCleanupFailure(null) { removeCloseQueryListener(tabId) }
        if (pane != null || content != null) {
            failure = captureCleanupFailure(failure) { requestWorkspaceTabClose(tabId) }
            failure = captureCleanupFailure(failure) { pane?.close() }
        }
        failure?.let { throw it }
    }

    private fun closeTabAfterRemoteSessionExit(tab: TerminalWorkspaceTab) {
        if (disposed) return

        val pane = panesByTabId.remove(tab.id) ?: return
        val content = contentsByTabId.remove(tab.id)
        var failure = captureCleanupFailure(null) { removeCloseQueryListener(tab.id) }
        failure = captureCleanupFailure(failure) { requestWorkspaceTabClose(tab.id) }
        failure = captureCleanupFailure(failure, pane::close)
        failure = captureCleanupFailure(failure) { content?.manager?.removeContent(content, true) }
        failure?.let { throw it }

        if (!closing && !hasOpenTabs()) {
            lastToolWindow?.let(::openDefaultTab)
        }
    }

    private fun closePendingTab(pendingId: String) {
        pendingTabsById.remove(pendingId)?.let { pending ->
            pending.closed = true
            pending.startedTab?.let { requestWorkspaceTabClose(it.id) }
        }
    }

    private fun requestWorkspaceTabClose(tabId: String) {
        val pending = pendingTabsById.entries.firstOrNull { it.value.startedTab?.id == tabId }
        if (pending != null) {
            pending.value.closed = true
            pendingTabsById.remove(pending.key)
        }
        workspace
            .tabSnapshot()
            .firstOrNull { it.id == tabId }
            ?.session
            ?.requestClose()
        if (!disposed && !workspace.closeTabAsync(tabId, ::reportWorkspaceCleanup)) {
            Logger
                .getInstance(KetraTermProjectTerminalService::class.java)
                .debug("Terminal cleanup is already queued or the workspace is shutting down")
        }
    }

    private fun reportWorkspaceCleanup(result: Result<Unit>) {
        result.exceptionOrNull()?.let {
            Logger.getInstance(KetraTermProjectTerminalService::class.java).warn("Terminal workspace cleanup failed", it)
        }
    }

    private fun publishTerminalStartupResult(
        pendingId: String,
        result: TerminalStartupResult,
    ) {
        val pendingTab = pendingTabsById[pendingId]
        if (pendingTab == null) {
            if (result is TerminalStartupResult.Started) {
                requestWorkspaceTabClose(result.tab.id)
            }
            return
        }

        when (result) {
            is TerminalStartupResult.Started -> {
                pendingTabsById.remove(pendingId)
                @Suppress("UsePropertyAccessSyntax")
                pendingTab.content.setDisposer(TerminalTabDisposable(result.tab.id))
            }
            is TerminalStartupResult.Failed -> showStartupFailure(pendingTab, result.error)
        }
    }

    private fun bindStartedTab(
        pendingTab: PendingTerminalTab,
        workspaceTab: TerminalWorkspaceTab,
        sourceProfile: TerminalProfile,
        onBound: (Throwable?) -> Unit,
    ) {
        workspaceTab.customTitle = pendingTab.restoredState?.customTitle
        // Pending sessions are not yet present when settings listeners reload installed panes.
        workspaceTab.showForegroundProcessName = KetraTermIntellijSettings.getInstance().state.showForegroundProcessName
        val clipboardSession = checkNotNull(clipboardSessions[workspaceTab.id])
        val pane =
            KetraTermTerminalPane.create(
                project = project,
                tab = workspaceTab,
                clipboard = clipboardSession.clipboard,
                onBound = onBound,
                hostActions =
                    KetraTermTerminalPaneHostActions(
                        openNewTabAction = ::openDefaultTabFromContextMenu,
                        canOpenTerminalHereAction = ::canOpenTerminalHere,
                        openTerminalHereAction = { tab -> openTerminalHere(tab, sourceProfile) },
                        closePaneAction = ::closePaneFromContextMenu,
                    ),
            )
        panesByTabId[workspaceTab.id] = pane
        replaceContent(pendingTab.container, pane.component)
        pendingTab.content.displayName = workspaceTab.title
        pendingTab.content.preferredFocusableComponent = pane.terminal
        installCloseQueryListener(pendingTab.content, workspaceTab)
        contentsByTabId[workspaceTab.id] = pendingTab.content
        clipboardSession.attach(pane.clipboardReadPrompt)
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
        pendingTab.content.displayName =
            pendingTab.restoredState?.customTitle ?: KetraTermBundle.message("terminal.tab.failed", profileName)
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

    private fun openOptions(settings: SwingSettings): TerminalWorkspaceOpenOptions =
        TerminalWorkspaceOpenOptions.create { draft ->
            draft.columns = settings.columns
            draft.rows = settings.rows
            draft.treatAmbiguousAsWide = settings.treatAmbiguousAsWide
            draft.maxHistory = KetraTermIntellijSettings.getInstance().state.scrollbackLines
            draft.pasteControlPolicy = settings.pasteControlPolicy
            draft.hostPolicy = KetraTermIntellijSettings.getInstance().createHostPolicy()
            draft.showForegroundProcessName = KetraTermIntellijSettings.getInstance().state.showForegroundProcessName
        }

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
            IntellijMessageDialogs.show(
                project,
                SwingDialogRequest(
                    KetraTermBundle.message("terminal.tab.close.title"),
                    KetraTermBundle.message("terminal.tab.close.question", tab.title),
                    SwingDialogRequest.Severity.WARNING,
                    listOf(KetraTermBundle.message("terminal.tab.close.terminate"), KetraTermBundle.message("terminal.tab.close.cancel")),
                ),
            )
        return answer == 0
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
        override fun tabOpened(tab: TerminalWorkspaceTab) {
            val pending = checkNotNull(startingTab)
            if (disposed || closing || pending.closed) throw CancellationException("Terminal startup cancelled")
            val binding = IntellijClipboardSession(pending.client, pending.applicationClient, tab.session, clipboardReader)
            clipboardSessions[tab.id] = binding
            pending.startedTab = tab
            if (disposed || closing || pending.closed) {
                Disposer.dispose(binding)
                throw CancellationException("Terminal startup cancelled")
            }
            val ready = CompletableFuture<Unit>()
            ApplicationManager.getApplication().invokeLater {
                try {
                    check(!disposed && !closing && !pending.closed) { "Terminal startup cancelled" }
                    bindStartedTab(pending, tab, checkNotNull(pending.preparedProfile)) { bindingFailure ->
                        try {
                            if (bindingFailure != null) throw bindingFailure
                            val policy = KetraTermIntellijSettings.getInstance().createHostPolicy()
                            checkNotNull(panesByTabId[tab.id]).terminal.setHostPolicy(policy) { failure ->
                                if (failure == null) ready.complete(Unit) else ready.completeExceptionally(failure)
                            }
                        } catch (failure: Throwable) {
                            ready.completeExceptionally(failure)
                        }
                    }
                } catch (failure: Throwable) {
                    ready.completeExceptionally(failure)
                }
            }
            // Startup owns no session/admission lock and output is still stopped.
            try {
                ready.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }

        override suspend fun readClipboard(
            tab: TerminalWorkspaceTab,
            request: TerminalClipboardReadRequest,
        ): TerminalClipboardReadResult {
            val binding = clipboardSessions[tab.id] ?: return TerminalClipboardReadResult.Unavailable
            return binding.read(request, SwingClipboardPrompts.readQuestion(tab.profile.displayName, messages = IntellijSwingHostMessages))
        }

        override fun commandFinished(
            tab: TerminalWorkspaceTab,
            metadata: TerminalShellIntegrationCommandMetadata,
        ) {
            val settings = KetraTermIntellijSettings.getInstance()
            val snapshot = settings.state
            val durationMillis = tab.session.shellIntegrationState.commandDurationMillis(metadata.recordId)
            if (!disposed &&
                !closing &&
                TerminalCommandFinishedAlerts.shouldNotify(
                    metadata = metadata,
                    durationMillis = durationMillis,
                    enabled = snapshot.commandFinishedAlertsEnabled,
                    minimumDurationSeconds = snapshot.commandFinishedAlertThresholdSeconds,
                    focused = false,
                    notificationsAllowed = tab.session.hostNotificationsAllowed,
                ) &&
                pendingCommandAlerts.add(tab.id)
            ) {
                try {
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            if (disposed || closing || project.isDisposed) return@invokeLater
                            showCommandFinishedAlert(
                                metadata = metadata,
                                durationMillis = durationMillis,
                                settings = settings.state,
                                pane = panesByTabId[tab.id]?.component,
                                focusOwner = commandAlertFocusOwner(),
                                sessionClosed = tab.session.isClosed,
                                notificationsAllowed = tab.session.hostNotificationsAllowed,
                                publish = commandAlertNotifier,
                            )
                        } finally {
                            pendingCommandAlerts.remove(tab.id)
                        }
                    }
                } catch (failure: Exception) {
                    pendingCommandAlerts.remove(tab.id)
                    throw failure
                }
            }
            if (snapshot.smartSuggestionsEnabled) {
                KetraTermCompletionService.getInstanceIfCreated()?.recordFinishedCommand(tab, metadata)
            }
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
            invokeLaterIfAlive {
                panesByTabId[tab.id]?.terminal?.refreshShellSuggestions()
                persistence?.capture()
            }
        }

        override fun startupCommandCancelled(tab: TerminalWorkspaceTab) {
            invokeLaterIfAlive {
                KetraTermIntellijNotifier.showNotification(
                    project,
                    KetraTermBundle.message("terminal.startup.commandSkipped.title"),
                    KetraTermBundle.message("terminal.startup.commandSkipped.body"),
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
            clipboardSessions[tab.id]?.write(event.text)
        }

        override fun terminalClipboardPrompt(
            tab: TerminalWorkspaceTab,
            event: TerminalClipboardPromptEvent,
        ) {
            if (!IntellijOsc52ClipboardSelections.targetsIdeClipboard(event.selection)) return
            val binding = clipboardSessions[tab.id] ?: return
            binding.postIfAlive {
                val allowed =
                    IntellijMessageDialogs.show(
                        project,
                        SwingClipboardPrompts.writeConfirmation(tab.profile.displayName, event.text, messages = IntellijSwingHostMessages),
                    ) == 0
                if (allowed && binding.isAlive) {
                    binding.clipboard.copyText(event.text)
                }
            }
        }

        override fun tabClosed(tabId: String) {
            clipboardSessions.remove(tabId)?.let(Disposer::dispose)
            invokeLaterIfAlive {
                contentsByTabId.remove(tabId)
                panesByTabId.remove(tabId)?.close()
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
        val client: IntellijClipboardClient,
        val applicationClient: IntellijClipboardClient,
    ) {
        @Volatile
        var startedTab: TerminalWorkspaceTab? = null
        var preparedProfile: TerminalProfile? = null
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
        ) : TerminalStartupResult

        data class Failed(
            val error: Throwable,
        ) : TerminalStartupResult
    }
}

internal object IntellijOsc52ClipboardSelections {
    fun targetsIdeClipboard(selection: String): Boolean = selection.isEmpty() || selection.indexOf('c') >= 0
}

internal inline fun captureCleanupFailure(
    previous: Throwable?,
    action: () -> Unit,
): Throwable? =
    try {
        action()
        previous
    } catch (failure: Throwable) {
        previous?.apply { if (this !== failure) addSuppressed(failure) } ?: failure
    }

/** EDT delivery boundary: reads current host state before requesting an IDE notification. */
internal fun showCommandFinishedAlert(
    metadata: TerminalShellIntegrationCommandMetadata,
    durationMillis: Long?,
    settings: KetraTermIntellijSettings.State,
    pane: Component?,
    focusOwner: Component?,
    sessionClosed: Boolean,
    notificationsAllowed: Boolean = true,
    publish: (String, String, NotificationLevel) -> Unit,
) {
    check(SwingUtilities.isEventDispatchThread()) { "Command alerts must be delivered on the EDT" }
    if (sessionClosed || pane == null) return
    val focused = focusOwner != null && (focusOwner === pane || SwingUtilities.isDescendingFrom(focusOwner, pane))
    if (!TerminalCommandFinishedAlerts.shouldNotify(
            metadata = metadata,
            durationMillis = durationMillis,
            enabled = settings.commandFinishedAlertsEnabled,
            minimumDurationSeconds = settings.commandFinishedAlertThresholdSeconds,
            focused = focused,
            notificationsAllowed = notificationsAllowed,
        )
    ) {
        return
    }
    val exitCode = metadata.exitCode
    val body =
        when (exitCode) {
            0 -> KetraTermBundle.message("terminal.commandFinished.success")
            null -> KetraTermBundle.message("terminal.commandFinished.unknown")
            else -> KetraTermBundle.message("terminal.commandFinished.failure", exitCode)
        }
    publish(
        KetraTermBundle.message("terminal.commandFinished.title"),
        body,
        if (exitCode == null || exitCode == 0) NotificationLevel.INFO else NotificationLevel.WARNING,
    )
}
