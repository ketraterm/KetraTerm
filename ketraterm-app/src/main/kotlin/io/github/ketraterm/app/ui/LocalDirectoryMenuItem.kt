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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.appMessages
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Path
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/** Resolves the captured directory only while its popup is open; all item state belongs to the EDT. */
internal class LocalDirectoryMenuItem(
    menu: JPopupMenu,
    uri: String?,
    private val isCurrent: () -> Boolean,
    open: (Path) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    resolve: (String?) -> Path? = LocalWorkingDirectoryResolver::resolve,
) : JMenuItem(appMessages.text("menu.openTerminalHere")) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var directory: Path? = null

    init {
        isEnabled = false
        toolTipText = appMessages.text("menu.noDirectory")
        addActionListener { directory?.takeIf { isCurrent() }?.let(open) }
        menu.addPopupMenuListener(
            object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
                    scope.launch {
                        try {
                            val result = withContext(ioDispatcher) { resolve(uri) }
                            if (!isCurrent()) return@launch
                            directory = result
                            isEnabled = result != null
                            toolTipText = if (result == null) appMessages.text("menu.noDirectory") else null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            System
                                .getLogger(
                                    LocalDirectoryMenuItem::class.java.name,
                                ).log(System.Logger.Level.WARNING, "Directory resolution failed", failure)
                        }
                    }
                }

                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                    scope.cancel()
                }

                override fun popupMenuCanceled(event: PopupMenuEvent) {
                    scope.cancel()
                }
            },
        )
    }
}
