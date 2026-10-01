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
package io.github.ketraterm.ui.swing.api

import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Host-owned action for a hyperlink discovered from visible terminal text.
 *
 * Reusable Swing UI stores only compact primitive ids in its paint and hit-test
 * paths. The host action is invoked only after explicit user activation, such
 * as Ctrl-left-click, and may route to an IDE, browser, file opener, or a
 * security prompt.
 */
fun interface SwingHyperlinkAction {
    /**
     * Opens the hyperlink target.
     *
     * @return `true` when the activation was handled.
     */
    fun open(): Boolean

    /** Opens from a captured gesture, preserving a host navigation popup's anchor. */
    fun open(event: MouseEvent): Boolean = open()

    /** Optional provider hover callback. Bounds are component-local; invoked on entry, never in paint. */
    fun mouseEntered(
        component: JComponent,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) = Unit

    /** Balances an entry when the pointer leaves, the occurrence changes, or the binding closes. */
    fun mouseExited() = Unit

    companion object {
        /**
         * Action that rejects activation.
         */
        @JvmField
        val NONE: SwingHyperlinkAction = SwingHyperlinkAction { false }
    }
}
