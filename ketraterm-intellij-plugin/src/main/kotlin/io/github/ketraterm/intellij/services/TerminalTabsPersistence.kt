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
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * Publishes tool-window tab changes on the EDT. Install after restoration and dispose before project
 * shutdown removes contents, so teardown cannot replace the last session with an empty snapshot.
 */
internal class TerminalTabsPersistence(
    private val storage: KetraTermTerminalTabsStorage,
    private val manager: ContentManager,
    parentDisposable: Disposable,
    private val snapshot: (Content) -> TerminalTabState?,
) : Disposable {
    private var stopped = false
    private val listener =
        object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) = capture()

            override fun contentRemoved(event: ContentManagerEvent) = capture()

            override fun selectionChanged(event: ContentManagerEvent) = capture()
        }

    init {
        manager.addContentManagerListener(listener)
        Disposer.register(parentDisposable, this)
    }

    /** Captures changes outside content-manager events, such as a custom title or working directory. */
    @RequiresEdt
    fun capture() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (stopped) return
        val contents = manager.contents
        val selectedContent = manager.selectedContent
        val tabs = ArrayList<TerminalTabState>(contents.size)
        var selectedTabIndex = -1
        for (content in contents) {
            val tab = snapshot(content) ?: continue
            if (content === selectedContent) selectedTabIndex = tabs.size
            tabs.add(tab)
        }
        storage.replace(TerminalTabsState(tabs, selectedTabIndex))
    }

    override fun dispose() {
        if (stopped) return
        stopped = true
        manager.removeContentManagerListener(listener)
    }
}
