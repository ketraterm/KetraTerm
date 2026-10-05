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

import io.github.ketraterm.render.api.TerminalRenderBufferKind

/**
 * Immutable selection range in one component binding and terminal layout.
 *
 * Create ranges through [SwingTerminal.createSelectionRange]. Retain a range to
 * restore it through [SwingTerminal.setSelection]. A range does not retain the
 * component or session. It describes a region, not a frozen text snapshot.
 *
 * Rows are absolute physical rows, including scrollback. A frame's first row is
 * `discardedCount + historySize - scrollbackOffset`. Columns are half-open
 * cell edges in `0..columns`. Direction follows the anchor and caret.
 * Linear columns follow stored text order; block columns follow visual order.
 * Painting and copying include complete wide cells and grapheme clusters.
 * Stored edges remain unchanged when they intersect a wide cell.
 *
 * Scrolling preserves coordinates. Reflow, history replacement, binding changes,
 * and observed buffer switches invalidate ranges. Evicted endpoints prevent
 * restoration. Ordinary cell edits change the text within the selected region.
 *
 * @property anchorColumn fixed cell edge.
 * @property anchorAbsoluteRow fixed absolute row.
 * @property caretColumn moving cell edge.
 * @property caretAbsoluteRow moving absolute row.
 * @property isBlock whether each row uses the same visual column interval.
 * @property buffer terminal buffer that contains the range.
 */
public class TerminalSelectionRange internal constructor(
    public val anchorColumn: Int,
    public val anchorAbsoluteRow: Long,
    public val caretColumn: Int,
    public val caretAbsoluteRow: Long,
    public val isBlock: Boolean,
    public val buffer: TerminalRenderBufferKind,
    internal val context: Any,
) {
    /** Whether the range selects no cells or row boundaries. */
    public val isEmpty: Boolean
        get() = anchorColumn == caretColumn && (isBlock || anchorAbsoluteRow == caretAbsoluteRow)
}
