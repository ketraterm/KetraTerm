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
package com.gagik.terminal.session

/**
 * Resolves primitive render-frame hyperlink ids to their OSC 8 target URI.
 *
 * Render frames deliberately expose only integer ids in the cell plane so UI
 * render loops can stay allocation-free. Session-level consumers may call this
 * resolver from user input paths, such as Ctrl-click hit testing, where looking
 * up host metadata is not part of frame painting.
 */
fun interface TerminalHyperlinkResolver {
    /**
     * Returns the target URI for [hyperlinkId], or `null` when the id is zero,
     * unknown, evicted by host policy, or otherwise not activatable.
     *
     * @param hyperlinkId primitive id copied from a render-frame cell.
     * @return target URI, or `null`.
     */
    fun uriForHyperlinkId(hyperlinkId: Int): String?

    companion object {
        /**
         * Resolver for sessions that do not expose hyperlink metadata.
         */
        @JvmField
        val NONE: TerminalHyperlinkResolver = TerminalHyperlinkResolver { null }
    }
}
