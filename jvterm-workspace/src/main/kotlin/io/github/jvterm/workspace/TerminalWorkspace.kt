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
package io.github.jvterm.workspace

import io.github.jvterm.protocol.NotificationLevel
import io.github.jvterm.pty.PtyEventListener
import io.github.jvterm.pty.PtyOptions
import io.github.jvterm.pty.TerminalSessions
import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.session.TerminalSession
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-neutral workspace that owns local terminal tabs and sessions.
 *
 * UI products adapt this model to visual containers such as Swing tabs or IDE
 * tool-window contents. This class does not know about UI widgets, painting,
 * input events, or platform actions.
 */
class TerminalWorkspace(
    private val listener: TerminalWorkspaceListener = TerminalWorkspaceListener.NONE,
) : AutoCloseable {
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
        val tabEventListener = tabEventListener(id, profile)
        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = profile.command,
                    environment = PtyOptions.defaultEnvironment() + profile.environment,
                    workingDirectory = profile.workingDirectory ?: DEFAULT_WORKING_DIRECTORY,
                    columns = options.columns,
                    rows = options.rows,
                    treatAmbiguousAsWide = options.treatAmbiguousAsWide,
                    maxHistory = options.maxHistory,
                    eventListener = tabEventListener,
                ),
            )
        val tab =
            TerminalWorkspaceTab(
                id = id,
                profile = profile,
                title = profile.displayName,
                session = session,
                onColorChanged = { t, color -> listener.colorChanged(t, color) },
                onTitleChanged = { t, titleText -> listener.titleChanged(t, titleText) },
            )
        tabs += tab
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

    private fun tabEventListener(
        tabId: String,
        profile: TerminalProfile,
    ): PtyEventListener =
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
                val nextTitle = title.ifBlank { profile.displayName }
                val tab = tabById(tabId) ?: return
                tab.updateDynamicTitle(nextTitle)
            }

            override fun resizeWindow(
                session: TerminalSession,
                rows: Int,
                columns: Int,
            ) {
                tabBySession(session)?.let { listener.resizeWindow(it, rows, columns) }
            }

            override fun showNotification(
                session: TerminalSession,
                title: String,
                body: String,
                level: NotificationLevel,
            ) {
                tabBySession(session)?.let { listener.showNotification(it, title, body, level) }
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
        private val DEFAULT_WORKING_DIRECTORY: Path = Path.of(System.getProperty("user.home"))
    }
}

/**
 * Initial terminal options for a workspace tab.
 *
 * @property columns initial terminal width in cells.
 * @property rows initial terminal height in rows.
 * @property treatAmbiguousAsWide width policy for future writes.
 * @property maxHistory max scrollback lines retained by the core buffer.
 */
data class TerminalWorkspaceOpenOptions(
    val columns: Int,
    val rows: Int,
    val treatAmbiguousAsWide: Boolean,
    val maxHistory: Int,
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
 * @property session running terminal session.
 */
class TerminalWorkspaceTab internal constructor(
    val id: String,
    val profile: TerminalProfile,
    title: String,
    val session: TerminalSession,
    private val onColorChanged: (TerminalWorkspaceTab, String?) -> Unit,
    private val onTitleChanged: (TerminalWorkspaceTab, String) -> Unit,
) {
    private var dynamicTitle: String = title

    /**
     * Current host-visible title for this tab.
     */
    val title: String
        get() = customTitle ?: dynamicTitle

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

    internal fun updateDynamicTitle(nextTitle: String) {
        if (dynamicTitle != nextTitle) {
            dynamicTitle = nextTitle
            if (customTitle == null) {
                onTitleChanged(this, nextTitle)
            }
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

    companion object {
        /**
         * Listener implementation that ignores every event.
         */
        val NONE: TerminalWorkspaceListener = object : TerminalWorkspaceListener {}
    }
}
