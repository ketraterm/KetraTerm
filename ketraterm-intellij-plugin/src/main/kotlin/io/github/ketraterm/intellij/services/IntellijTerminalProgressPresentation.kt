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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.settings.KetraTermIntellijProfileIcons
import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import io.github.ketraterm.ui.swing.host.SwingTerminalProgressAnimation
import io.github.ketraterm.ui.swing.host.SwingTerminalProgressIcon
import io.github.ketraterm.workspace.TerminalWorkspaceTab

/** Owns the tool-window selection listener and transient progress decorations on the EDT. */
internal class IntellijTerminalProgressPresentation(
    private val window: ToolWindow,
) : Disposable {
    private val manager = window.contentManager
    private val component = window.component
    private val baseWindowTitle = window.title.orEmpty()
    private var disposed = false
    private val profileIcons = KetraTermIntellijProfileIcons()
    private val icons = java.util.IdentityHashMap<Content, SwingTerminalProgressIcon>()
    private val animatedIcons = ArrayList<SwingTerminalProgressIcon>()
    private val animation =
        SwingTerminalProgressAnimation(component) { phase ->
            var index = 0
            while (index < animatedIcons.size) {
                animatedIcons[index].phase = phase
                index++
            }
            component.repaint()
        }
    private val listener =
        object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) = refreshWindow()

            override fun contentRemoved(event: ContentManagerEvent) {
                icons.remove(event.content)
                refreshWindow()
            }
        }

    init {
        manager.addContentManagerListener(listener)
    }

    fun bind(
        content: Content,
        tab: TerminalWorkspaceTab,
    ) {
        content.putUserData(TAB, tab)
        icons[content] = SwingTerminalProgressIcon(16)
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
        refreshTab(content, tab)
    }

    fun refreshTab(
        content: Content,
        tab: TerminalWorkspaceTab,
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        val progress = tab.session.progress.value
        content.displayName = tab.title
        content.description = progressTitle(tab.title, progress)
        val icon = icons[content] ?: return
        icon.progress = progress
        icon.setAccessibleIconDescription(content.description)
        content.icon = if (progress.state == TerminalProgressState.HIDDEN) profileIcons.icon(tab.profile.kind) else icon
        refreshWindow()
        component.repaint()
    }

    private fun refreshWindow() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || window.isDisposed) return
        val progress =
            manager.selectedContent
                ?.getUserData(TAB)
                ?.session
                ?.progress
                ?.value ?: TerminalProgress.HIDDEN
        window.title = progressTitle(baseWindowTitle, progress)
        animatedIcons.clear()
        for (icon in icons.values) {
            if (icon.progress.state == TerminalProgressState.INDETERMINATE) animatedIcons.add(icon)
        }
        animation.active = animatedIcons.isNotEmpty()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        animation.close()
        icons.clear()
        animatedIcons.clear()
        try {
            manager.removeContentManagerListener(listener)
        } finally {
            for (content in manager.contents) {
                content.getUserData(TAB)?.let {
                    content.displayName = it.title
                    content.icon = profileIcons.icon(it.profile.kind)
                    content.description = it.title
                }
                content.putUserData(TAB, null)
            }
            if (!window.isDisposed) window.title = baseWindowTitle
        }
    }

    private companion object {
        val TAB = Key.create<TerminalWorkspaceTab>("KetraTerm.progressTab")
    }
}

/** Formats presentation metadata only when the state changes; no terminal text or framework is involved. */
internal fun progressTitle(
    title: String,
    progress: TerminalProgress,
): String {
    val status =
        when (progress.state) {
            TerminalProgressState.HIDDEN -> return title
            TerminalProgressState.NORMAL -> KetraTermBundle.message("terminal.progress.normal", progress.percentage)
            TerminalProgressState.ERROR -> KetraTermBundle.message("terminal.progress.error", progress.percentage)
            TerminalProgressState.INDETERMINATE -> KetraTermBundle.message("terminal.progress.indeterminate")
            TerminalProgressState.WARNING -> KetraTermBundle.message("terminal.progress.warning", progress.percentage)
        }
    return if (title.isEmpty()) "[$status]" else "$title [$status]"
}
