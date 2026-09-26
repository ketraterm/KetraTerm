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
package io.github.ketraterm.host

import io.github.ketraterm.protocol.TerminalClipboardSelection

/**
 * A validated read query admitted by the host adapter.
 * [permission] and [maxDecodedBytes] describe admission, not a permanent grant.
 * Session execution rechecks policy before access and before committing output.
 */
data class TerminalClipboardReadRequest(
    val selection: TerminalClipboardSelection,
    val permission: TerminalClipboardPermission,
    val maxDecodedBytes: Int,
) {
    init {
        require(maxDecodedBytes >= 0) { "Clipboard byte limit must be non-negative" }
    }
}

/** Content-free execution result, distinct from the adapter's admission audit. */
enum class TerminalClipboardReadOutcome {
    /** Valid text, including empty text, was written as a complete reply. */
    SENT,

    /** Read policy or user consent denied access; the reply carries no data. */
    DENIED,

    /** No provider or requested text selection was available. */
    UNAVAILABLE,

    /** Malformed UTF-16 or a raw/encoded payload limit rejected the result. */
    INVALID_DATA,

    /** Provider or transport failure; exception details are deliberately excluded. */
    FAILED,

    /** Another read still owns the slot; no provider, prompt, or reply was added. */
    BUSY,

    /** The deadline expired; an empty reply requires an idle writer and bounded commitment time. */
    TIMED_OUT,

    /** Lifecycle or a restrictive policy change retired uncommitted work. */
    CANCELLED,
}

/** Contains no clipboard data or provider exception details. */
data class TerminalClipboardReadAuditEvent(
    val selection: TerminalClipboardSelection,
    val outcome: TerminalClipboardReadOutcome,
)
