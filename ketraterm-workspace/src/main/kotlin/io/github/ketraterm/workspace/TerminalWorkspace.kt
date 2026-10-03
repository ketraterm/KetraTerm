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
package io.github.ketraterm.workspace

import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.host.TerminalClipboardPromptEvent
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.pty.PtyEventListener
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.pty.TerminalSessions
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.*
import io.github.ketraterm.shell.integration.OscShellIntegration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-neutral workspace that owns local terminal tabs and sessions.
 *
 * UI products adapt this model to visual containers such as Swing tabs or IDE
 * tool-window contents. This class does not know about UI widgets, painting,
 * input events, or platform actions.
 * Optional process-title and startup-notification observers report failures
 * through the JVM logger and stop independently of essential session observation.
 */
public class TerminalWorkspace internal constructor(
    private val listener: TerminalWorkspaceListener,
    private val sessionFactory: TerminalWorkspaceSessionFactory,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    /**
     * Creates a workspace backed by local PTY sessions.
     *
     * @param listener host-neutral workspace event listener.
     */
    public constructor(listener: TerminalWorkspaceListener = TerminalWorkspaceListener.NONE) : this(
        listener = listener,
        sessionFactory = LocalPtyWorkspaceSessionFactory,
    )

    private val tabs = ArrayList<TerminalWorkspaceTab>(INITIAL_TAB_CAPACITY)
    private val stateLock = Any()
    private val sessionStateJobs = HashMap<String, Job>()
    private val shellMetadataRegistrations = HashMap<String, AutoCloseable>()
    private val workspaceJob = SupervisorJob()
    private val workspaceScope =
        CoroutineScope(workspaceJob + workerDispatcher + CoroutineName("terminal-workspace"))
    private val nextTabNumber = AtomicInteger(1)
    private var selectedTabId: String? = null
    private var selectionRevision = 0L
    private var closed = false

    internal val isCoroutineScopeActive: Boolean
        get() = workspaceJob.isActive

    internal val sessionCollectionCount: Int
        get() = synchronized(stateLock) { sessionStateJobs.size }

    /**
     * Returns a stable snapshot of currently open tabs.
     *
     * @return a list containing all currently open [TerminalWorkspaceTab]s.
     */
    public fun tabSnapshot(): List<TerminalWorkspaceTab> = synchronized(stateLock) { tabs.toList() }

    /**
     * Returns the currently selected tab, or `null` when no tabs are open.
     *
     * @return the selected [TerminalWorkspaceTab], or null if none.
     */
    public fun selectedTab(): TerminalWorkspaceTab? = synchronized(stateLock) { selectedTabId?.let(::tabByIdLocked) }

    /**
     * Opens a new local PTY-backed tab. Publishes it through
     * [TerminalWorkspaceListener.tabOpened] before starting output delivery.
     * Publication or startup failure closes the session and removes the tab.
     * A closed workspace rejects new tabs before creating a session.
     *
     * @param profile launch profile for the new process.
     * @param options initial session dimensions and terminal policy.
     * @return opened workspace tab.
     */
    public fun openTab(
        profile: TerminalProfile,
        options: TerminalWorkspaceOpenOptions,
    ): TerminalWorkspaceTab {
        synchronized(stateLock) { check(!closed) { "workspace is closed" } }
        val id = "terminal-${nextTabNumber.getAndIncrement()}"
        val session = sessionFactory.create(profile, options, tabEventListener(id))
        try {
            check(session.state.value === TerminalSessionState.Created) { "Workspace factory must return an unstarted session" }
            val tab =
                TerminalWorkspaceTab(
                    id = id,
                    profile = profile,
                    title = profile.displayName,
                    session = session,
                    onColorChanged = { t, color -> listener.colorChanged(t, color) },
                    onTitleChanged = { t, titleText -> listener.titleChanged(t, titleText) },
                    onCurrentWorkingDirectoryChanged = { t, uri -> listener.currentWorkingDirectoryChanged(t, uri) },
                    showForegroundProcessName = options.showForegroundProcessName,
                )
            synchronized(stateLock) {
                check(!closed) { "workspace is closed" }
                tabs += tab
                val commandFinishedRegistration =
                    session.shellIntegrationState.addCommandFinishedListener { metadata ->
                        if (!session.isClosed) tabBySession(session)?.let { listener.commandFinished(it, metadata) }
                    }
                val directoryRegistration =
                    session.shellIntegrationState.addCurrentWorkingDirectoryListener { uri ->
                        if (!session.isClosed) tabBySession(session)?.updateCurrentWorkingDirectoryUri(uri)
                    }
                shellMetadataRegistrations[id] =
                    AutoCloseable {
                        commandFinishedRegistration.close()
                        directoryRegistration.close()
                    }
            }
            selectTab(id)
            listener.tabOpened(tab)
            session.currentWorkingDirectoryUri()?.let(tab::updateCurrentWorkingDirectoryUri)
            session.start(options.columns, options.rows)

            val stateJob =
                workspaceScope.launch {
                    supervisorScope {
                        val processTitleJob =
                            launch(presentationFailureHandler) {
                                tab.processTitleEnabled.collectLatest { enabled ->
                                    if (enabled) session.foregroundProcessName.collect(tab::updateForegroundProcessName)
                                }
                            }
                        val startupJob =
                            session.startupCommandStatus?.let { status ->
                                launch(presentationFailureHandler) {
                                    if (status.first { it != TerminalStartupCommandStatus.WAITING } ==
                                        TerminalStartupCommandStatus.CANCELLED_BY_INPUT
                                    ) {
                                        listener.startupCommandCancelled(tab)
                                    }
                                }
                            }
                        try {
                            val closed = session.state.filterIsInstance<TerminalSessionState.Closed>().first()
                            startupJob?.cancel()
                            processTitleJob.cancelAndJoin()
                            var failure = captureCleanupFailure(null) { tab.updateForegroundProcessName(null) }
                            failure =
                                captureCleanupFailure(failure) {
                                    if (!closed.event.locallyRequested) {
                                        tabBySession(session)?.let {
                                            listener.sessionClosed(it, closed.event.exitCode, closed.event.failure)
                                        }
                                    }
                                }
                            failure?.let { throw it }
                        } finally {
                            synchronized(stateLock) { shellMetadataRegistrations.remove(id) }?.close()
                        }
                    }
                }
            synchronized(stateLock) {
                if (!closed && tabByIdLocked(id) === tab) {
                    sessionStateJobs[id] = stateJob
                } else {
                    stateJob.cancel(CancellationException("Terminal workspace tab closed during startup"))
                }
            }
            return tab
        } catch (failure: Throwable) {
            try {
                closeTab(id)
            } catch (cleanup: Throwable) {
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            // Also covers failure before the tab entered the workspace registry.
            try {
                session.close()
            } catch (cleanup: Throwable) {
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * Selects an existing tab.
     *
     * @param id tab id.
     */
    public fun selectTab(id: String) {
        synchronized(stateLock) {
            require(tabByIdLocked(id) != null) { "unknown terminal tab id: $id" }
            selectedTabId = id
            selectionRevision++
        }
        listener.tabSelected(id)
    }

    /**
     * Closes an existing tab and its session.
     * Attempts session cleanup and every close/selection notification even when
     * a callback throws. Rethrows the first failure with later failures suppressed.
     * Reentrant selection or closure supersedes this call's pending selection notification.
     *
     * @param id tab id.
     */
    public fun closeTab(id: String) {
        val result =
            synchronized(stateLock) {
                val index = tabs.indexOfFirst { it.id == id }
                if (index < 0) return
                val tab = tabs.removeAt(index)
                sessionStateJobs.remove(id)?.cancel(CancellationException("Terminal workspace tab closed"))
                if (selectedTabId == id) {
                    selectedTabId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
                }
                Triple(tab, ++selectionRevision, shellMetadataRegistrations.remove(id))
            }
        val (tab, revision, shellMetadataRegistration) = result
        var failure: Throwable? = null
        failure = captureCleanupFailure(failure) { shellMetadataRegistration?.close() }
        failure = captureCleanupFailure(failure) { tab.showForegroundProcessName = false }
        failure = captureCleanupFailure(failure) { tab.session.close() }
        failure = captureCleanupFailure(failure) { listener.tabClosed(id) }
        failure =
            captureCleanupFailure(failure) {
                val nextSelectedTabId = synchronized(stateLock) { selectedTabId.takeIf { selectionRevision == revision } }
                nextSelectedTabId?.let(listener::tabSelected)
            }
        failure?.let { throw it }
    }

    /**
     * Applies host settings that are shared across all open sessions.
     *
     * @param palette terminal color palette.
     * @param treatAmbiguousAsWide width policy for future writes.
     */
    public fun applySettings(
        palette: TerminalColorPalette,
        treatAmbiguousAsWide: Boolean,
    ) {
        for (tab in tabSnapshot()) {
            tab.session.setThemePalette(palette)
            tab.session.setTreatAmbiguousAsWide(treatAmbiguousAsWide)
        }
    }

    /**
     * Rejects new tabs, attempts every tab cleanup, and cancels the workspace scope.
     * Rethrows the first failure with later failures suppressed. Repeated or
     * reentrant calls are no-ops; they do not wait for an active close to finish.
     */
    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        var failure: Throwable? = null
        try {
            while (true) {
                val id = synchronized(stateLock) { tabs.lastOrNull()?.id } ?: break
                failure = captureCleanupFailure(failure) { closeTab(id) }
            }
        } finally {
            workspaceScope.cancel(CancellationException("Terminal workspace closed"))
        }
        failure?.let { throw it }
    }

    private inline fun captureCleanupFailure(
        previous: Throwable?,
        action: () -> Unit,
    ): Throwable? =
        try {
            action()
            previous
        } catch (failure: Throwable) {
            previous?.apply { if (this !== failure) addSuppressed(failure) } ?: failure
        }

    private fun tabEventListener(tabId: String): PtyEventListener =
        object : PtyEventListener {
            override fun bell(session: TerminalSession) {
                tabBySession(session)?.let { listener.bell(it) }
            }

            override fun iconTitleChanged(
                session: TerminalSession,
                title: String,
            ) = Unit

            override fun windowTitleChanged(
                session: TerminalSession,
                title: String,
            ) {
                val tab = tabById(tabId) ?: return
                tab.updateDynamicTitle(title.takeIf { it.isNotBlank() })
            }

            override fun resizeWindow(
                session: TerminalSession,
                rows: Int,
                columns: Int,
            ) {
                tabBySession(session)?.let { listener.resizeWindow(it, rows, columns) }
            }

            override fun columnModeChanged(
                session: TerminalSession,
                rows: Int,
                columns: Int,
            ) {
                tabBySession(session)?.let { listener.columnModeChanged(it, rows, columns) }
            }

            override fun moveWindow(
                session: TerminalSession,
                x: Int,
                y: Int,
            ) {
                tabBySession(session)?.let { listener.moveWindow(it, x, y) }
            }

            override fun minimizeWindow(session: TerminalSession) {
                tabBySession(session)?.let { listener.minimizeWindow(it) }
            }

            override fun deminimizeWindow(session: TerminalSession) {
                tabBySession(session)?.let { listener.deminimizeWindow(it) }
            }

            override fun raiseWindow(session: TerminalSession) {
                tabBySession(session)?.let { listener.raiseWindow(it) }
            }

            override fun lowerWindow(session: TerminalSession) {
                tabBySession(session)?.let { listener.lowerWindow(it) }
            }

            override fun setMaximized(
                session: TerminalSession,
                maximize: Boolean,
            ) {
                tabBySession(session)?.let { listener.setMaximized(it, maximize) }
            }

            override fun shellIntegrationMarker(
                session: TerminalSession,
                event: ShellIntegrationEvent,
            ) {
                tabBySession(session)?.let { listener.shellIntegrationMarker(it, event) }
            }

            override fun showNotification(
                session: TerminalSession,
                title: String,
                body: String,
                level: NotificationLevel,
            ) {
                tabBySession(session)?.let { listener.showNotification(it, title, body, level) }
            }

            override fun paletteChanged(
                session: TerminalSession,
                palette: TerminalColorPalette,
            ) {
                tabBySession(session)?.let { listener.paletteChanged(it, palette) }
            }

            override fun hyperlinkRegistered(
                session: TerminalSession,
                hyperlinkId: Int,
                uri: String,
                id: String?,
            ) {
                tabBySession(session)?.let { listener.hyperlinkRegistered(it, hyperlinkId, uri, id) }
            }

            override fun hyperlinkRemoved(
                session: TerminalSession,
                hyperlinkId: Int,
            ) {
                tabBySession(session)?.let { listener.hyperlinkRemoved(it, hyperlinkId) }
            }

            override fun hyperlinksCleared(session: TerminalSession) {
                tabBySession(session)?.let { listener.hyperlinksCleared(it) }
            }

            override suspend fun readClipboard(
                session: TerminalSession,
                request: TerminalClipboardReadRequest,
            ): TerminalClipboardReadResult {
                currentCoroutineContext().ensureActive()
                val tab = tabById(tabId) ?: return TerminalClipboardReadResult.Unavailable
                if (tab.session !== session) return TerminalClipboardReadResult.Unavailable
                return listener.readClipboard(tab, request)
            }

            override fun terminalClipboardWrite(
                session: TerminalSession,
                event: TerminalClipboardWriteEvent,
            ) {
                tabBySession(session)?.let { listener.terminalClipboardWrite(it, event) }
            }

            override fun terminalClipboardPrompt(
                session: TerminalSession,
                event: TerminalClipboardPromptEvent,
            ) {
                tabBySession(session)?.let { listener.terminalClipboardPrompt(it, event) }
            }

            override fun listenerFailed(
                session: TerminalSession,
                exception: Exception,
            ) {
                tabBySession(session)?.let { listener.listenerFailed(it, exception) }
            }
        }

    private fun tabById(id: String): TerminalWorkspaceTab? = synchronized(stateLock) { tabByIdLocked(id) }

    private fun tabByIdLocked(id: String): TerminalWorkspaceTab? = tabs.firstOrNull { it.id == id }

    private fun tabBySession(session: TerminalSession): TerminalWorkspaceTab? =
        synchronized(stateLock) { tabs.firstOrNull { it.session === session } }

    private companion object {
        private const val INITIAL_TAB_CAPACITY = 4
        private val presentationFailureHandler =
            CoroutineExceptionHandler { _, failure ->
                System
                    .getLogger(TerminalWorkspace::class.java.name)
                    .log(System.Logger.Level.WARNING, "Workspace presentation observer failed", failure)
            }
    }
}

internal fun interface TerminalWorkspaceSessionFactory {
    /** Returns a created session; the workspace publishes its tab before starting output delivery. */
    fun create(
        profile: TerminalProfile,
        options: TerminalWorkspaceOpenOptions,
        eventListener: PtyEventListener,
    ): TerminalSession
}

private object LocalPtyWorkspaceSessionFactory : TerminalWorkspaceSessionFactory {
    override fun create(
        profile: TerminalProfile,
        options: TerminalWorkspaceOpenOptions,
        eventListener: PtyEventListener,
    ): TerminalSession {
        val launchProfile =
            TerminalShellIntegrationBootstrap.apply(
                profile = profile,
                enabled = options.shellIntegrationEnabled,
            )
        return TerminalSessions.createLocalPty(
            PtyOptions(
                command = launchProfile.command,
                environment = PtyOptions.defaultEnvironment() + launchProfile.environment,
                workingDirectory = launchProfile.workingDirectory ?: DEFAULT_WORKING_DIRECTORY,
                columns = options.columns,
                rows = options.rows,
                treatAmbiguousAsWide = options.treatAmbiguousAsWide,
                inputPolicy =
                    PtyOptions
                        .defaultInputPolicy()
                        .copy(pasteControlPolicy = options.pasteControlPolicy),
                maxHistory = options.maxHistory,
                eventListener = eventListener,
                hostPolicy = options.hostPolicy,
                startupCommand = launchProfile.startupCommand,
                modeReportCapabilities = options.modeReportCapabilities,
                shellIntegration = OscShellIntegration,
            ),
        )
    }

    private val DEFAULT_WORKING_DIRECTORY: Path = Path.of(System.getProperty("user.home"))
}

/**
 * Initial terminal options for a workspace tab.
 *
 * Constructor, [copy], and destructuring signatures are part of the public ABI.
 *
 * @property columns initial terminal width in cells.
 * @property rows initial terminal height in rows.
 * @property treatAmbiguousAsWide width policy for future writes.
 * @property maxHistory max scrollback lines retained by the core buffer.
 * @property pasteControlPolicy paste payload transformation applied before
 * host-bound input emission.
 * @property shellIntegrationEnabled whether supported launch profiles should
 * install shell hooks that emit OSC 7 and OSC 133 metadata.
 * @property hostPolicy safety policy.
 * @property showForegroundProcessName whether detected processes provide automatic title fallbacks.
 * @property modeReportCapabilities implemented host actions from TerminalHostModeCapability.
 */
public data class TerminalWorkspaceOpenOptions(
    val columns: Int,
    val rows: Int,
    val treatAmbiguousAsWide: Boolean,
    val maxHistory: Int,
    val pasteControlPolicy: PasteControlPolicy = PasteControlPolicy.PRESERVE,
    val shellIntegrationEnabled: Boolean = true,
    val hostPolicy: HostPolicy = HostPolicy(),
    val showForegroundProcessName: Boolean = true,
    val modeReportCapabilities: Int = 0,
) {
    init {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(rows > 0) { "rows must be > 0, was $rows" }
        require(maxHistory >= 0) { "maxHistory must be >= 0, was $maxHistory" }
    }
}

/**
 * Open workspace tab and its running session.
 *
 * @property id stable tab id.
 * @property profile launch profile used to create this tab.
 * @property title current host-visible tab title.
 * @property currentWorkingDirectoryUri latest directory URI from the selected shell model, or
 *   `null` before one is reported.
 * @property session running terminal session.
 */
public class TerminalWorkspaceTab internal constructor(
    public val id: String,
    public val profile: TerminalProfile,
    title: String,
    public val session: TerminalSession,
    private val onColorChanged: (TerminalWorkspaceTab, String?) -> Unit,
    private val onTitleChanged: (TerminalWorkspaceTab, String) -> Unit,
    private val onCurrentWorkingDirectoryChanged: (TerminalWorkspaceTab, String) -> Unit,
    showForegroundProcessName: Boolean = true,
) {
    private val titleLock = Any()
    private val mutableProcessTitleEnabled = MutableStateFlow(showForegroundProcessName)
    internal val processTitleEnabled = mutableProcessTitleEnabled.asStateFlow()
    private var foregroundProcessTitle: String? = null
    private var dynamicTitle: String = title
    private var applicationTitleActive: Boolean = title != profile.displayName
    private var directoryTitle: String? = null

    @Volatile
    private var currentWorkingDirectory: String? = null

    /**
     * Current host-visible title for this tab.
     */
    public val title: String
        get() = synchronized(titleLock) { titleLocked() }

    private fun titleLocked(): String =
        customTitle ?: if (applicationTitleActive) dynamicTitle else foregroundProcessTitle ?: directoryTitle ?: dynamicTitle

    /** Enables process-title tracking; disabling immediately restores the existing title fallback. */
    public var showForegroundProcessName: Boolean
        get() = processTitleEnabled.value
        set(enabled) {
            val changedTitle =
                updateTitleState {
                    if (mutableProcessTitleEnabled.value == enabled) return
                    mutableProcessTitleEnabled.value = enabled
                    // A rapid off/on pair can be conflated before the collector restarts.
                    foregroundProcessTitle = acceptedForegroundProcessTitle(if (enabled) session.foregroundProcessName.value else null)
                }
            changedTitle?.let { onTitleChanged(this, it) }
        }

    internal fun updateForegroundProcessName(name: String?) {
        val changedTitle =
            updateTitleState {
                foregroundProcessTitle = acceptedForegroundProcessTitle(name)
            }
        changedTitle?.let { onTitleChanged(this, it) }
    }

    private fun acceptedForegroundProcessTitle(name: String?): String? =
        if (processTitleEnabled.value && !session.isClosed) {
            name?.let(::sanitizeTitle)?.takeUnless(::isLaunchExecutableTitle)
        } else {
            null
        }

    /**
     * Latest current-working-directory URI from the selected shell model.
     *
     * The value is safe to read from host UI threads and remains `null` until
     * a directory is observed. Model publications update it synchronously until
     * the session closes; the tab then retains its last observed value.
     */
    public val currentWorkingDirectoryUri: String?
        get() = currentWorkingDirectory

    /**
     * Optional user title, taking precedence over application, process, and directory titles.
     */
    public var customTitle: String? = null
        get() = synchronized(titleLock) { field }
        set(value) {
            val changedTitle =
                updateTitleState {
                    field = value
                }
            changedTitle?.let { onTitleChanged(this, it) }
        }

    internal fun updateDynamicTitle(nextTitle: String?) {
        val changedTitle =
            updateTitleState {
                val acceptedTitle = nextTitle?.trim()?.takeIf(String::isNotEmpty)?.takeUnless(::isLaunchExecutableTitle)
                applicationTitleActive = acceptedTitle != null
                dynamicTitle = acceptedTitle ?: profile.displayName
            }
        changedTitle?.let { onTitleChanged(this, it) }
    }

    internal fun updateCurrentWorkingDirectoryUri(uri: String) {
        val changedTitle =
            updateTitleState {
                if (currentWorkingDirectory == uri) return
                currentWorkingDirectory = uri
                directoryTitle = workingDirectoryTitle(uri)
            }
        onCurrentWorkingDirectoryChanged(this, uri)
        changedTitle?.let { onTitleChanged(this, it) }
    }

    /**
     * Optional custom color representation for this tab (e.g. hex string "#3b82f6").
     */
    public var color: String? = null
        set(value) {
            if (field != value) {
                field = value
                onColorChanged(this, value)
            }
        }

    // All title sources share this lock; listeners run outside it to allow host re-entry.
    private inline fun updateTitleState(update: () -> Unit): String? =
        synchronized(titleLock) {
            val previous = titleLocked()
            update()
            titleLocked().takeUnless { it == previous }
        }

    private fun workingDirectoryTitle(uriValue: String): String? {
        val path =
            try {
                URI(uriValue).path
            } catch (_: URISyntaxException) {
                return null
            }
        if (path.isNullOrEmpty()) return null
        val withoutTrailingSeparators = path.trimEnd('/', '\\')
        val candidate =
            if (withoutTrailingSeparators.isEmpty()) {
                "/"
            } else {
                withoutTrailingSeparators.substringAfterLast('/').substringAfterLast('\\')
            }
        return sanitizeTitle(candidate)
    }

    private fun sanitizeTitle(value: String): String? =
        value
            .filter { character ->
                !character.isISOControl() && Character.getType(character) != Character.FORMAT.toInt()
            }.take(MAX_FALLBACK_TITLE_LENGTH)
            .trim()
            .ifEmpty { null }

    private fun isLaunchExecutableTitle(candidate: String): Boolean {
        val launchExecutable = profile.command.firstOrNull() ?: return false
        return executableTitleKey(candidate) == executableTitleKey(launchExecutable)
    }

    private fun executableTitleKey(value: String): String =
        value
            .trim()
            .trim('"')
            .replace('\\', '/')
            .substringAfterLast('/')
            .lowercase(Locale.ROOT)

    private companion object {
        private const val MAX_FALLBACK_TITLE_LENGTH = 256
    }
}

/**
 * Host-neutral workspace events.
 */
public interface TerminalWorkspaceListener {
    /**
     * Effective palette change for an attached tab. The immutable value may be
     * retained. Metadata callbacks run synchronously under the session mutation
     * lock: return promptly and schedule UI work without waiting or reentering
     * terminal mutation. There is no initial replay; read the session's palette
     * for current state. Events before tab attachment or after removal are ignored.
     */
    public fun paletteChanged(
        tab: TerminalWorkspaceTab,
        palette: TerminalColorPalette,
    ): Unit = Unit

    /** New accepted OSC 8 registry entry for an attached tab; no browser action is implied. */
    public fun hyperlinkRegistered(
        tab: TerminalWorkspaceTab,
        hyperlinkId: Int,
        uri: String,
        id: String?,
    ): Unit = Unit

    /** An evicted OSC 8 identity is no longer resolvable in this tab's session. */
    public fun hyperlinkRemoved(
        tab: TerminalWorkspaceTab,
        hyperlinkId: Int,
    ): Unit = Unit

    /** Hard reset cleared this tab's nonempty OSC 8 registry. */
    public fun hyperlinksCleared(tab: TerminalWorkspaceTab): Unit = Unit

    /** The user typed before shell readiness, so the configured startup command was not submitted. */
    public fun startupCommandCancelled(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called after a tab is registered and selected, before its session starts.
     * Establish host routing here; output delivery starts after this callback
     * returns. Throwing aborts the open and closes the prepared session.
     *
     * @param tab opened tab.
     */
    public fun tabOpened(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called when a tab color changes.
     *
     * @param tab tab whose color changed.
     * @param color new color representation (e.g. hex string) or null if reset.
     */
    public fun colorChanged(
        tab: TerminalWorkspaceTab,
        color: String?,
    ): Unit = Unit

    /**
     * Called after workspace selection changes.
     *
     * @param tabId selected tab id.
     */
    public fun tabSelected(tabId: String): Unit = Unit

    /**
     * Called after a tab is closed.
     *
     * @param tabId closed tab id.
     */
    public fun tabClosed(tabId: String): Unit = Unit

    /**
     * Called when a tab emits a terminal bell event.
     *
     * @param tab tab that emitted the bell.
     */
    public fun bell(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called when a tab requests a window/grid resize.
     *
     * @param tab tab requesting resize.
     * @param rows target row count.
     * @param columns target column count.
     */
    public fun resizeWindow(
        tab: TerminalWorkspaceTab,
        rows: Int,
        columns: Int,
    ): Unit = Unit

    /**
     * Receives a logical column switch for an open tab. Hosts may schedule an
     * optional window resize without changing the session grid.
     * See [io.github.ketraterm.host.HostEventSink.columnModeChanged].
     */
    public fun columnModeChanged(
        tab: TerminalWorkspaceTab,
        rows: Int,
        columns: Int,
    ): Unit = Unit

    /**
     * Called when the shell requests moving the terminal window.
     *
     * @param tab tab that received the request.
     * @param x target x position on screen.
     * @param y target y position on screen.
     */
    public fun moveWindow(
        tab: TerminalWorkspaceTab,
        x: Int,
        y: Int,
    ): Unit = Unit

    /**
     * Called when the shell requests minimizing the terminal window.
     *
     * @param tab tab that received the request.
     */
    public fun minimizeWindow(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called when the shell requests deminimizing (restoring) the terminal window.
     *
     * @param tab tab that received the request.
     */
    public fun deminimizeWindow(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called when the shell requests raising the terminal window.
     *
     * @param tab tab that received the request.
     */
    public fun raiseWindow(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called when the shell requests lowering the terminal window.
     *
     * @param tab tab that received the request.
     */
    public fun lowerWindow(tab: TerminalWorkspaceTab): Unit = Unit

    /**
     * Called when the shell requests maximizing or restoring the terminal window.
     *
     * @param tab tab that received the request.
     * @param maximize true to maximize, false to restore.
     */
    public fun setMaximized(
        tab: TerminalWorkspaceTab,
        maximize: Boolean,
    ): Unit = Unit

    /**
     * Called when a tab receives an OSC 133 shell integration marker.
     *
     * @param tab tab that received the marker.
     * @param event typed marker event.
     */
    public fun shellIntegrationMarker(
        tab: TerminalWorkspaceTab,
        event: ShellIntegrationEvent,
    ): Unit = Unit

    /**
     * Called synchronously for each completion published by the selected shell model.
     *
     * Metadata is captured for the completed command, without replay or conflation.
     * The callback runs on the producer thread and must not block. Registration
     * ends when the tab or session closes; an already dispatched callback may finish.
     */
    public fun commandFinished(
        tab: TerminalWorkspaceTab,
        metadata: TerminalShellIntegrationCommandMetadata,
    ): Unit = Unit

    /**
     * Called when a tab title changes.
     *
     * Sources may notify from different threads. Hosts dispatching to a UI thread should
     * read [TerminalWorkspaceTab.title] there so a queued callback cannot restore a stale title.
     *
     * @param tab tab whose title changed.
     * @param title new title.
     */
    public fun titleChanged(
        tab: TerminalWorkspaceTab,
        title: String,
    ): Unit = Unit

    /**
     * Called after a tab observes a new current-working-directory URI in its shell model.
     *
     * Repeated reports of the same URI are coalesced. The tab property is
     * updated before this callback runs. The callback runs synchronously on the
     * producer thread and must not block. Observation stops when the tab or session closes.
     *
     * @param tab tab whose working directory changed.
     * @param uri absolute `file://` URI reported by the shell.
     */
    public fun currentWorkingDirectoryChanged(
        tab: TerminalWorkspaceTab,
        uri: String,
    ): Unit = Unit

    /**
     * Called when the PTY event bridge reports a listener failure.
     *
     * @param tab tab associated with the failure.
     * @param exception failure raised by the listener bridge.
     */
    public fun listenerFailed(
        tab: TerminalWorkspaceTab,
        exception: Exception,
    ): Unit = Unit

    /**
     * Called when the tab's terminal session closes because the process exited
     * or the transport failed.
     *
     * Local application-requested tab closes are reported through [tabClosed]
     * instead. UI products should use this callback to remove, restart, or mark
     * dead terminal panes according to product policy.
     *
     * @param tab tab whose session stopped.
     * @param exitCode process exit code reported by the transport, or null when
     * unknown.
     * @param failure transport failure, or null for a normal process exit.
     */
    public fun sessionClosed(
        tab: TerminalWorkspaceTab,
        exitCode: Int?,
        failure: Throwable?,
    ): Unit = Unit

    /**
     * Called when a tab requests a desktop notification.
     *
     * @param tab tab that requested the notification.
     * @param title notification title.
     * @param body notification message body.
     * @param level notification severity level.
     */
    public fun showNotification(
        tab: TerminalWorkspaceTab,
        title: String,
        body: String,
        level: NotificationLevel,
    ): Unit = Unit

    /**
     * Called when a tab receives an OSC 52 clipboard write request that was
     * allowed by host policy and decoded to text.
     *
     * UI products own platform clipboard access. Implementations should avoid
     * logging or retaining [event.text].
     *
     * @param tab tab that received the request.
     * @param event decoded clipboard write request.
     */
    public fun terminalClipboardWrite(
        tab: TerminalWorkspaceTab,
        event: TerminalClipboardWriteEvent,
    ): Unit = Unit

    /**
     * Called when a tab receives an OSC 52 clipboard write request that requires
     * product-host user approval.
     *
     * UI products own prompting and platform clipboard access. Implementations
     * should avoid logging or retaining [event.text].
     *
     * @param tab tab that received the request.
     * @param event decoded clipboard prompt request.
     */
    public fun terminalClipboardPrompt(
        tab: TerminalWorkspaceTab,
        event: TerminalClipboardPromptEvent,
    ): Unit = Unit

    /**
     * Resolves a read for its owning [tab], independently of the selected tab.
     *
     * Invoked after [tabOpened] returns, on the session I/O dispatcher without
     * workspace or parser/input locks. Asynchronously posted pane creation may
     * still be pending: await that pane and earlier posted writes as required
     * by [TerminalClipboardReader]. Session cancellation covers the host
     * operation within the original deadline. Provider failures
     * are audited without details; they are not delivered to [listenerFailed].
     * The default reports unavailable data without accessing a clipboard.
     */
    public suspend fun readClipboard(
        tab: TerminalWorkspaceTab,
        request: TerminalClipboardReadRequest,
    ): TerminalClipboardReadResult = TerminalClipboardReadResult.Unavailable

    public companion object {
        /**
         * Listener implementation that ignores every event.
         */
        public val NONE: TerminalWorkspaceListener = object : TerminalWorkspaceListener {}
    }
}
