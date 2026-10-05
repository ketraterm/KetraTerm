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

/**
 * Observes committed selection changes synchronously on the Swing EDT.
 *
 * Null means no selection. Events describe complete ranges, including offscreen
 * rows. Viewport scrolling and text edits within unchanged bounds emit no event.
 * Partial eviction clips the range; full eviction clears it.
 *
 * Registration emits no initial event. Read [SwingTerminal.currentSelectionRange]
 * for the current value. Equal bounds in the same context emit no event.
 * Listeners run in registration order, outside terminal frame leases.
 * A reentrant change supersedes the remaining delivery of the older event.
 * Removed listeners receive no further calls. New listeners start with the next change.
 * Ordinary callback exceptions are logged, and remaining listeners continue.
 * Cancellation and errors propagate after commitment and stop that event's delivery.
 * Binding cleanup finishes before its final notification. Output notifications
 * follow applied frames and can coalesce with frame publication.
 */
public fun interface TerminalSelectionListener {
    /** Receives immutable ranges that callers may retain. */
    public fun selectionChanged(
        previous: TerminalSelectionRange?,
        current: TerminalSelectionRange?,
    )
}
