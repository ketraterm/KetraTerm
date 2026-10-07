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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Shared Swing host context-menu item builder for terminal-owned actions.
 *
 * This helper deliberately lives outside the reusable terminal component. Hosts
 * opt in when they want KetraTerm's default action vocabulary, and can append
 * host-specific tab, pane, or IDE actions afterwards.
 */
public object SwingTerminalContextMenuItems {
    /**
     * Appends link-aware and generic terminal actions to [menu].
     *
     * @param menu popup menu receiving items.
     * @param request terminal context-menu request.
     * @param openSearch host-owned search UI action.
     * @return `true` when at least one item was added.
     */
    public fun addTerminalActions(
        menu: JPopupMenu,
        request: SwingTerminalContextMenuRequest,
        openSearch: () -> Unit,
    ): Boolean = addTerminalActions(menu, request, openSearch, SwingHostMessages.forLocale())

    /** Appends terminal actions using [messages] for every label; decisions and actions stay unchanged. */
    public fun addTerminalActions(
        menu: JPopupMenu,
        request: SwingTerminalContextMenuRequest,
        openSearch: () -> Unit,
        messages: SwingHostMessages,
    ): Boolean {
        request.hyperlink?.let { link ->
            menu.add(
                JMenuItem(messages.message("menu.openLink")).apply {
                    addActionListener { link.open() }
                },
            )
            if (link.uri != null) {
                menu.add(
                    JMenuItem(messages.message("menu.copyLink")).apply {
                        addActionListener { link.copyUri() }
                    },
                )
            }
            menu.addSeparator()
        }

        menu.add(
            JMenuItem(messages.message("menu.copy")).apply {
                isEnabled = request.hasSelection()
                addActionListener { request.copySelection() }
            },
        )
        menu.add(
            JMenuItem(messages.message("menu.paste")).apply {
                addActionListener { request.pasteClipboard() }
            },
        )
        menu.add(
            JMenuItem(messages.message("menu.selectAll")).apply {
                addActionListener { request.selectAll() }
            },
        )
        menu.add(
            JMenuItem(messages.message("menu.search")).apply {
                addActionListener { openSearch() }
            },
        )
        menu.add(
            JMenuItem(messages.message("menu.clearScreen")).apply {
                addActionListener { request.clearScreen() }
            },
        )
        return true
    }
}
