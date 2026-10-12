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

/** Search-bar operations that embedding hosts can bind through their own action system. */
public enum class SwingTerminalSearchAction {
    /** Select the next match. */
    NEXT_MATCH,

    /** Select the previous match. */
    PREVIOUS_MATCH,

    /** Close search and return focus to the terminal. */
    CLOSE,

    /** Toggle case-sensitive matching. */
    TOGGLE_MATCH_CASE,

    /** Toggle whole-word matching. */
    TOGGLE_WHOLE_WORD,

    /** Toggle regular-expression matching. */
    TOGGLE_REGEX,
}
