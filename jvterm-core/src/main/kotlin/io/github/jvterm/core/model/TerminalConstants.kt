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
package io.github.jvterm.core.model

/**
 * Sentinel values stored directly in a Line's raw codepoint array.
 *
 * Cell value encoding contract (all values are Ints):
 * The gap between SPACER (-1) and the first valid cluster handle (-2) is intentional.
 * It lets any consumer distinguish the three cases with a single comparison:
 *   value > 0 → codepoint
 *   value == 0 → empty
 *   value == -1 → spacer
 *   value <= -2 → cluster handle
 */
internal object TerminalConstants {
    /** A blank, unwritten cell. */
    const val EMPTY: Int = 0

    /**
     * Placeholder occupying the right column of a 2-cell wide character.
     * The cell immediately to the left holds the leader codepoint or cluster handle.
     */
    const val WIDE_CHAR_SPACER: Int = -1

    /**
     * All cluster handles are <= this value.
     * Using `rawValue <= CLUSTER_HANDLE_MAX` reliably detects any handle.
     */
    const val CLUSTER_HANDLE_MAX: Int = -2
}
