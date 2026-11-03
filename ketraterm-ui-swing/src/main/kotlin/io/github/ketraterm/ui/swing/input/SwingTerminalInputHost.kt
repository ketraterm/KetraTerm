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
package io.github.ketraterm.ui.swing.input

import io.github.ketraterm.session.TerminalSession
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Component services used by [SwingTerminalInputController] to route keyboard and focus events.
 *
 * All members are accessed synchronously on the EDT. The controller maps keys and orders
 * invalidation before input admission; the host owns suggestion lifecycle, viewport, focus,
 * and painting policy. The controller borrows [session] and never starts or closes it.
 * Callback failures propagate and abort the remaining routing of the current event.
 */
internal interface SwingTerminalInputHost {
    /** Currently bound session, or null while unbound. Read again after callbacks that can rebind the host. */
    val session: TerminalSession?

    /**
     * Updates modifier-dependent mouse routing and hyperlink hover without submitting input.
     *
     * @param modifiers AWT extended modifier mask using [InputEvent] constants.
     */
    fun updatePointerModifiers(modifiers: Int)

    /** Restarts the shared blink phase and repaints cursor/text regions whose visibility changed. */
    fun resetCursorBlink()

    /** Returns to live output after input admission, cancelling pending viewport motion. */
    fun scrollToLiveViewport()

    /**
     * Updates component focus state and reconciles automatic suggestions and pointer feedback.
     * Focus reporting to the shell remains the controller's responsibility.
     *
     * @param focused true after focus gain, false after focus loss.
     */
    fun setTerminalFocused(focused: Boolean)

    /** Schedules repaint of cursor regions affected by focus or visibility changes. */
    fun repaintCursorState()

    /**
     * Offers a press to host shortcuts after suggestion handling and before terminal encoding.
     *
     * @param event original AWT key press, including modifiers and physical location.
     * @return true when the host owns the press and its corresponding typed/release lifecycle;
     *   false to continue terminal input routing.
     * @throws Exception if a configured shortcut callback fails; the key must not be submitted.
     */
    fun handleHostKeyPressed(event: KeyEvent): Boolean

    /**
     * Offers a press to the suggestion surface before host shortcuts or terminal encoding.
     *
     * @param event original AWT key press.
     * @return true when suggestion handling owns the key lifecycle, including a guarded action
     *   that invalidated its own publication; false when routing should continue.
     * @throws Exception if suggestion selection, acceptance, dismissal, or a host callback fails.
     */
    fun handleShellSuggestionKeyPressed(event: KeyEvent): Boolean

    /**
     * Revokes stale requests and editing capabilities before shell-bound input is submitted.
     *
     * Cancels provider work and notifies invalidation listeners synchronously. Previously painted
     * rows may survive as a noninteractive preview while automatic results refresh. Call
     * [hideShellSuggestions] first when the input also requires immediate visual dismissal.
     *
     * @throws Exception if request cleanup or an invalidation listener fails; input must not be submitted.
     */
    fun invalidateShellSuggestions()

    /**
     * Cancels active suggestion work and removes the surface, including any retained preview.
     *
     * Used for non-editing command input, focus loss, and rejected input admission. Repeated
     * calls are safe and produce no negative preference feedback. This does not replace the
     * pre-admission notification performed by [invalidateShellSuggestions].
     *
     * @throws Exception if request cleanup or a presentation callback fails.
     */
    fun hideShellSuggestions()
}
