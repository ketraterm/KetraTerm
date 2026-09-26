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
package io.github.ketraterm.session

import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardReadRequest

/**
 * Clipboard operation bound to the owning session and product lifecycle.
 *
 * For [TerminalClipboardPermission.PROMPT], obtain consent for this request
 * before accessing any clipboard. Check coroutine cancellation immediately
 * before native access. Cancellation must dismiss consent; a blocking native
 * call must retain its worker slot until it actually returns.
 *
 * Resolve selectors in order: c/s select the clipboard, p selects the native
 * primary selection, and q/0..7 are unavailable unless explicitly implemented.
 * Never substitute clipboard text for an unavailable primary selection.
 * An available empty string succeeds. Await earlier posted allowed writes and
 * this session's host readiness before reading; never route through the selected tab.
 */
fun interface TerminalClipboardReader {
    /** Called on the session I/O dispatcher; no parser/input monitor is held. */
    suspend fun read(request: TerminalClipboardReadRequest): TerminalClipboardReadResult
}

/** A platform result; failures may also be thrown and are audited without details. */
sealed interface TerminalClipboardReadResult {
    /** Clipboard text, kept out of generated toString/equals implementations. */
    class Text(
        val text: String,
    ) : TerminalClipboardReadResult

    /** Consent was declined. */
    data object Denied : TerminalClipboardReadResult

    /** No requested selection contains available text. */
    data object Unavailable : TerminalClipboardReadResult
}
