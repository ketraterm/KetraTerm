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
import io.github.ketraterm.host.TerminalClipboardWriteEvent
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.pty.PtyEventListener
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.pty.TerminalSessions
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalSession
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
 */
class TerminalWorkspace internal constructor(
    private val listener: TerminalWorkspaceListener,
    private val sessionFactory: TerminalWorkspaceSessionFactory,
) : AutoCloseable {
    /**
     * Creates a workspace backed by local PTY sessions.
     *
     * @param listener host-neutral workspace event listener.
     */
    constructor(listener: TerminalWorkspaceListener = TerminalWorkspaceListener.NONE) : this(
        listener = listener,
        sessionFactory = LocalPtyWorkspaceSessionFactory,
    )

    private val tabs = ArrayList<TerminalWorkspaceTab>(INITIAL_TAB_CAPACITY)
    private val nextTabNumber = AtomicInteger(1)
    private var selectedTabId: String? = null

    /**
     * Returns a stable snapshot of currently open tabs.
     *
     * @return a list containing all currently open [TerminalWorkspaceTab]s.
     */
    fun tabSnapshot(): List<TerminalWorkspaceTab> = tabs.toList()

    /**
     * Returns the currently selected tab, or `null` when no tabs are open.
     *
     * @return the selected [TerminalWorkspaceTab], or null if none.
     */
    fun selectedTab(): TerminalWorkspaceTab? = selectedTabId?.let(::tabById)

    /**
     * Opens a new local PTY-backed tab.
     *
     * @param profile launch profile for the new process.
     * @param options initial session dimensions and terminal policy.
     * @return opened workspace tab.
     */
    fun openTab(
        profile: TerminalProfile,
        options: TerminalWorkspaceOpenOptions,
    ): TerminalWorkspaceTab {
        val id = "terminal-${nextTabNumber.getAndIncrement()}"
        val tabEventListener = tabEventListener(id)
        val session = sessionFactory.open(profile, options, tabEventListener)
        val tab =
            TerminalWorkspaceTab(
                id = id,
                profile = profile,
                title = profile.displayName,
                session = session,
                onColorChanged = { t, color -> listener.colorChanged(t, color) },
                onTitleChanged = { t, titleText -> listener.titleChanged(t, titleText) },
                onCurrentWorkingDirectoryChanged = { t, uri -> listener.currentWorkingDirectoryChanged(t, uri) },
            )
        tabs += tab
        session.currentWorkingDirectoryUri()?.let(tab::updateCurrentWorkingDirectoryUri)
        selectTab(id)
        listener.tabOpened(tab)
        return tab
    }

    /**
     * Selects an existing tab.
     *
     * @param id tab id.
     */
    fun selectTab(id: String) {
        require(tabById(id) != null) { "unknown terminal tab id: $id" }
        selectedTabId = id
        listener.tabSelected(id)
    }

    /**
     * Closes an existing tab and its session.
     *
     * @param id tab id.
     */
    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        tab.session.close()
        if (selectedTabId == id) {
            selectedTabId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id
        }
        listener.tabClosed(id)
        selectedTabId?.let(listener::tabSelected)
    }

    /**
     * Applies host settings that are shared across all open sessions.
     *
     * @param palette terminal color palette.
     * @param treatAmbiguousAsWide width policy for future writes.
     */
    fun applySettings(
        palette: TerminalColorPalette,
        treatAmbiguousAsWide: Boolean,
    ) {
        for (tab in tabs) {
            tab.session.setThemePalette(palette)
            tab.session.setTreatAmbiguousAsWide(treatAmbiguousAsWide)
            tab.session.notifyRenderDirty()
        }
    }

    override fun close() {
        while (tabs.isNotEmpty()) {
            closeTab(tabs.last().id)
        }
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

            override fun currentWorkingDirectoryChanged(
                session: TerminalSession,
                uri: String,
            ) {
                val tab = tabById(tabId) ?: return
                tab.updateCurrentWorkingDirectoryUri(uri)
            }

            override fun resizeWindow(
                session: TerminalSession,
                rows: Int,
                columns: Int,
            ) {
                tabBySession(session)?.let { listener.resizeWindow(it, rows, columns) }
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

            override fun terminalClipboardWrite(
                session: TerminalSession,
                event: TerminalClipboardWriteEvent,
            ) {
                tabBySession(session)?.let { listener.terminalClipboardWrite(it, event) }
            }

            override fun listenerFailed(
                session: TerminalSession,
                exception: Exception,
            ) {
                tabBySession(session)?.let { listener.listenerFailed(it, exception) }
            }
        }

    private fun tabById(id: String): TerminalWorkspaceTab? = tabs.firstOrNull { it.id == id }

    private fun tabBySession(session: TerminalSession): TerminalWorkspaceTab? = tabs.firstOrNull { it.session === session }

    private companion object {
        private const val INITIAL_TAB_CAPACITY = 4
    }
}

internal fun interface TerminalWorkspaceSessionFactory {
    fun open(
        profile: TerminalProfile,
        options: TerminalWorkspaceOpenOptions,
        eventListener: PtyEventListener,
    ): TerminalSession
}

private object LocalPtyWorkspaceSessionFactory : TerminalWorkspaceSessionFactory {
    override fun open(
        profile: TerminalProfile,
        options: TerminalWorkspaceOpenOptions,
        eventListener: PtyEventListener,
    ): TerminalSession {
        val launchProfile =
            TerminalShellIntegrationBootstrap.apply(
                profile = profile,
                enabled = options.shellIntegrationEnabled,
            )
        return TerminalSessions.localPty(
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
                        .copy(pasteSanitizationPolicy = options.pasteSanitizationPolicy),
                maxHistory = options.maxHistory,
                eventListener = eventListener,
                hostPolicy = options.hostPolicy,
            ),
        )
    }

    private val DEFAULT_WORKING_DIRECTORY: Path = Path.of(System.getProperty("user.home"))
}

/**
 * Initial terminal options for a workspace tab.
 *
 * @property columns initial terminal width in cells.
 * @property rows initial terminal height in rows.
 * @property treatAmbiguousAsWide width policy for future writes.
 * @property maxHistory max scrollback lines retained by the core buffer.
 * @property pasteSanitizationPolicy paste payload transformation applied before
 * host-bound input emission.
 * @property shellIntegrationEnabled whether supported launch profiles should
 * install shell hooks that emit OSC 7 and OSC 133 metadata.
 * @property hostPolicy safety policy.
 */
data class TerminalWorkspaceOpenOptions(
    val columns: Int,
    val rows: Int,
    val treatAmbiguousAsWide: Boolean,
    val maxHistory: Int,
    val pasteSanitizationPolicy: PasteSanitizationPolicy = PasteSanitizationPolicy.RAW,
    val shellIntegrationEnabled: Boolean = true,
    val hostPolicy: HostPolicy = HostPolicy(),
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
 * @property currentWorkingDirectoryUri latest valid OSC 7 directory URI, or
 *   `null` before one is reported.
 * @property session running terminal session.
 */
class TerminalWorkspaceTab internal constructor(
    val id: String,
    val profile: TerminalProfile,
    title: String,
    val session: TerminalSession,
    private val onColorChanged: (TerminalWorkspaceTab, String?) -> Unit,
    private val onTitleChanged: (TerminalWorkspaceTab, String) -> Unit,
    private val onCurrentWorkingDirectoryChanged: (TerminalWorkspaceTab, String) -> Unit,
) {
    private var dynamicTitle: String = title
    private var applicationTitleActive: Boolean = title != profile.displayName
    private var directoryTitle: String? = null

    @Volatile
    private var currentWorkingDirectory: String? = null

    /**
     * Current host-visible title for this tab.
     */
    val title: String
        get() = customTitle ?: if (applicationTitleActive) dynamicTitle else directoryTitle ?: dynamicTitle

    /**
     * Latest host-validated OSC 7 current-working-directory URI.
     *
     * The value is safe to read from host UI threads and remains `null` until
     * the shell reports a directory.
     */
    val currentWorkingDirectoryUri: String?
        get() = currentWorkingDirectory

    /**
     * Optional custom title set by the user. If null, falls back to the dynamic PTY title.
     */
    var customTitle: String? = null
        set(value) {
            if (field != value) {
                field = value
                onTitleChanged(this, title)
            }
        }

    internal fun updateDynamicTitle(nextTitle: String?) {
        val previousTitle = title
        val acceptedTitle = nextTitle?.trim()?.takeIf(String::isNotEmpty)?.takeUnless(::isLaunchExecutableTitle)
        applicationTitleActive = acceptedTitle != null
        dynamicTitle = acceptedTitle ?: profile.displayName
        notifyTitleChanged(previousTitle)
    }

    internal fun updateCurrentWorkingDirectoryUri(uri: String) {
        if (currentWorkingDirectory == uri) return
        val previousTitle = title
        currentWorkingDirectory = uri
        directoryTitle = workingDirectoryTitle(uri)
        onCurrentWorkingDirectoryChanged(this, uri)
        notifyTitleChanged(previousTitle)
    }

    private fun notifyTitleChanged(previousTitle: String) {
        val nextTitle = title
        if (previousTitle != nextTitle) {
            onTitleChanged(this, nextTitle)
        }
    }

    /**
     * Optional custom color representation for this tab (e.g. hex string "#3b82f6").
     */
    var color: String? = null
        set(value) {
            if (field != value) {
                field = value
                onColorChanged(this, value)
            }
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
        val sanitized =
            candidate
                .filter { character ->
                    !character.isISOControl() && Character.getType(character) != Character.FORMAT.toInt()
                }.take(MAX_DIRECTORY_TITLE_LENGTH)
                .trim()
        return sanitized.ifEmpty { null }
    }

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
        private const val MAX_DIRECTORY_TITLE_LENGTH = 256
    }
}

/**
 * Host-neutral workspace events.
 */
interface TerminalWorkspaceListener {
    /**
     * Called after a tab is opened and selected.
     *
     * @param tab opened tab.
     */
    fun tabOpened(tab: TerminalWorkspaceTab) = Unit

    /**
     * Called when a tab color changes.
     *
     * @param tab tab whose color changed.
     * @param color new color representation (e.g. hex string) or null if reset.
     */
    fun colorChanged(
        tab: TerminalWorkspaceTab,
        color: String?,
    ) = Unit

    /**
     * Called after workspace selection changes.
     *
     * @param tabId selected tab id.
     */
    fun tabSelected(tabId: String) = Unit

    /**
     * Called after a tab is closed.
     *
     * @param tabId closed tab id.
     */
    fun tabClosed(tabId: String) = Unit

    /**
     * Called when a tab emits a terminal bell event.
     *
     * @param tab tab that emitted the bell.
     */
    fun bell(tab: TerminalWorkspaceTab) = Unit

    /**
     * Called when a tab requests a window/grid resize.
     *
     * @param tab tab requesting resize.
     * @param rows target row count.
     * @param columns target column count.
     */
    fun resizeWindow(
        tab: TerminalWorkspaceTab,
        rows: Int,
        columns: Int,
    ) = Unit

    /**
     * Called when the shell requests moving the terminal window.
     *
     * @param tab tab that received the request.
     * @param x target x position on screen.
     * @param y target y position on screen.
     */
    fun moveWindow(
        tab: TerminalWorkspaceTab,
        x: Int,
        y: Int,
    ) = Unit

    /**
     * Called when the shell requests minimizing the terminal window.
     *
     * @param tab tab that received the request.
     */
    fun minimizeWindow(tab: TerminalWorkspaceTab) = Unit

    /**
     * Called when the shell requests deminimizing (restoring) the terminal window.
     *
     * @param tab tab that received the request.
     */
    fun deminimizeWindow(tab: TerminalWorkspaceTab) = Unit

    /**
     * Called when the shell requests raising the terminal window.
     *
     * @param tab tab that received the request.
     */
    fun raiseWindow(tab: TerminalWorkspaceTab) = Unit

    /**
     * Called when the shell requests lowering the terminal window.
     *
     * @param tab tab that received the request.
     */
    fun lowerWindow(tab: TerminalWorkspaceTab) = Unit

    /**
     * Called when the shell requests maximizing or restoring the terminal window.
     *
     * @param tab tab that received the request.
     * @param maximize true to maximize, false to restore.
     */
    fun setMaximized(
        tab: TerminalWorkspaceTab,
        maximize: Boolean,
    ) = Unit

    /**
     * Called when a tab receives an OSC 133 shell integration marker.
     *
     * @param tab tab that received the marker.
     * @param event typed marker event.
     */
    fun shellIntegrationMarker(
        tab: TerminalWorkspaceTab,
        event: ShellIntegrationEvent,
    ) = Unit

    /**
     * Called when a tab title changes.
     *
     * @param tab tab whose title changed.
     * @param title new title.
     */
    fun titleChanged(
        tab: TerminalWorkspaceTab,
        title: String,
    ) = Unit

    /**
     * Called after a tab accepts a new OSC 7 current-working-directory URI.
     *
     * Repeated reports of the same URI are coalesced. The tab property is
     * updated before this callback runs.
     *
     * @param tab tab whose working directory changed.
     * @param uri absolute `file://` URI reported by the shell.
     */
    fun currentWorkingDirectoryChanged(
        tab: TerminalWorkspaceTab,
        uri: String,
    ) = Unit

    /**
     * Called when the PTY event bridge reports a listener failure.
     *
     * @param tab tab associated with the failure.
     * @param exception failure raised by the listener bridge.
     */
    fun listenerFailed(
        tab: TerminalWorkspaceTab,
        exception: Exception,
    ) = Unit

    /**
     * Called when a tab requests a desktop notification.
     *
     * @param tab tab that requested the notification.
     * @param title notification title.
     * @param body notification message body.
     * @param level notification severity level.
     */
    fun showNotification(
        tab: TerminalWorkspaceTab,
        title: String,
        body: String,
        level: NotificationLevel,
    ) = Unit

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
    fun terminalClipboardWrite(
        tab: TerminalWorkspaceTab,
        event: TerminalClipboardWriteEvent,
    ) = Unit

    companion object {
        /**
         * Listener implementation that ignores every event.
         */
        val NONE: TerminalWorkspaceListener = object : TerminalWorkspaceListener {}
    }
}
