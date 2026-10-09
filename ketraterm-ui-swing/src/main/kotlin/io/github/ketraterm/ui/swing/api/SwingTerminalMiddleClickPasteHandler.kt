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
 * Host-owned handling of an enabled local middle-button paste gesture.
 *
 * Installed through [SwingHostServices.middleClickPasteHandler]. Application mouse reporting
 * takes priority unless Shift forces local interaction. The hook runs once on the EDT at press,
 * only on the component's local paste route with a displayed frame and a nonclosed bound session.
 * It replaces the built-in clipboard read for that gesture; returning never triggers fallback.
 * Keyboard, menu, and programmatic paste operations do not invoke this hook.
 */
public fun interface SwingTerminalMiddleClickPasteHandler {
    /**
     * Handles [request] immediately or retains it for deferred completion.
     *
     * Hosts own clipboard access, consent, and asynchronous work. Read the captured source,
     * then call [SwingTerminalMiddleClickPasteRequest.complete] on the EDT, or [SwingTerminalMiddleClickPasteRequest.cancel]
     * to decline. Callback failures cancel unresolved completion and propagate unchanged.
     * An already admitted paste cannot be retracted if the callback subsequently fails.
     */
    public fun handlePaste(request: SwingTerminalMiddleClickPasteRequest)
}
