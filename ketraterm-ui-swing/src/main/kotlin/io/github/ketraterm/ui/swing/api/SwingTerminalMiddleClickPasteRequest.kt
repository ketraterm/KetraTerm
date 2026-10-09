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

import io.github.ketraterm.ui.swing.settings.SwingPasteSource
import javax.swing.SwingUtilities

/**
 * One-use paste authority captured for a local middle-button press.
 *
 * Immutable metadata can be retained for host-owned asynchronous work. Completion and cancellation
 * belong to the EDT. Requests from separate presses are independent; the host owns any supersession
 * or cancellation of its clipboard work. Retaining this request retains its source component.
 *
 * @property terminal source terminal component.
 * @property source clipboard source captured from the press-time settings.
 * @property x component-local press coordinate for host presentation.
 * @property y component-local press coordinate for host presentation.
 * @property forcedByShift whether Shift requested local interaction.
 */
public class SwingTerminalMiddleClickPasteRequest internal constructor(
    public val terminal: SwingTerminal,
    public val source: SwingPasteSource,
    public val x: Int,
    public val y: Int,
    public val forcedByShift: Boolean,
    private var completeAction: ((String) -> Boolean)?,
) {
    /**
     * Completes this request with host-approved [text], without reading a clipboard.
     *
     * An EDT attempt consumes the request before callbacks, even for empty text, stale authority,
     * rejected admission, or a propagated callback failure. Later attempts return `false`.
     * Calls outside the EDT return `false` without consuming the request.
     *
     * The original binding must remain current, including across suggestion invalidation callbacks.
     * Rebinding, even to the same session, unbinding, closure, and disposal reject completion.
     * Settings changes retain the issued request and its captured [source]; the session's current
     * paste policy and modes apply at completion. Nonempty eligible input invalidates suggestions.
     *
     * @return `true` only when the original session admits this paste. Admission does not promise
     * transport completion or shell acknowledgement.
     */
    public fun complete(text: String): Boolean {
        if (!SwingUtilities.isEventDispatchThread()) return false
        val action = completeAction ?: return false
        completeAction = null
        return action(text)
    }

    /**
     * Permanently declines this request without reading or submitting text. Idempotent on the EDT.
     * This does not cancel host-owned asynchronous work or retract an already admitted paste.
     *
     * @throws IllegalStateException when called outside the EDT.
     */
    public fun cancel() {
        check(SwingUtilities.isEventDispatchThread()) { "Paste request cancellation must run on the EDT" }
        completeAction = null
    }
}
