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

/**
 * Permission policy for terminal-originated clipboard protocols such as OSC 52.
 *
 * This policy describes what would be permitted by an embedding host. The host
 * adapter never writes to a platform clipboard directly; it audits every
 * request and emits decoded write payloads only when the configured policy
 * permits the operation or requires a product-host write prompt. Allowlists,
 * read responses, and platform clipboard access remain product-host
 * responsibilities.
 *
 * @property origin trust boundary of the terminal session that produced the
 * request.
 * @property localWritePermission write policy for local PTY-style sessions.
 * @property remoteWritePermission write policy for SSH or otherwise remote
 * sessions.
 * @property readPermission policy for clipboard read/query requests. This
 * defaults to deny because read responses can exfiltrate user clipboard data.
 * @property allowlisted whether the embedding product has already matched this
 * session/profile/remote against its clipboard allowlist.
 * @property maxDecodedBytes maximum decoded clipboard payload size accepted for
 * write requests before the adapter reports a size denial.
 */
data class TerminalClipboardPolicy(
    val origin: TerminalClipboardOrigin = TerminalClipboardOrigin.REMOTE,
    val localWritePermission: TerminalClipboardPermission = TerminalClipboardPermission.DENY,
    val remoteWritePermission: TerminalClipboardPermission = TerminalClipboardPermission.DENY,
    val readPermission: TerminalClipboardPermission = TerminalClipboardPermission.DENY,
    val allowlisted: Boolean = false,
    val maxDecodedBytes: Int = DEFAULT_MAX_DECODED_BYTES,
) {
    init {
        require(maxDecodedBytes >= 0) {
            "maxDecodedBytes must be non-negative, got $maxDecodedBytes"
        }
    }

    internal val writePermission: TerminalClipboardPermission
        get() =
            when (origin) {
                TerminalClipboardOrigin.LOCAL -> localWritePermission
                TerminalClipboardOrigin.REMOTE -> remoteWritePermission
            }

    companion object {
        /**
         * Default maximum decoded OSC 52 write payload size.
         */
        const val DEFAULT_MAX_DECODED_BYTES: Int = 1 * 1024 * 1024
    }
}

/**
 * Trust boundary for terminal output that requested clipboard access.
 */
enum class TerminalClipboardOrigin {
    /**
     * Local process controlled by this host.
     */
    LOCAL,

    /**
     * Remote or otherwise less-trusted process, such as SSH.
     */
    REMOTE,
}

/**
 * Permission decision mode for a terminal clipboard operation family.
 */
enum class TerminalClipboardPermission {
    /**
     * Reject the operation without prompting.
     */
    DENY,

    /**
     * Surface the request so a product host can prompt the user.
     */
    PROMPT,

    /**
     * Permit only when the embedding product marked the session/profile/remote
     * as allowlisted in [TerminalClipboardPolicy].
     */
    ALLOWLIST,

    /**
     * The policy permits the operation.
     */
    ALLOW,
}

/**
 * OSC 52 operation class.
 */
enum class TerminalClipboardOperation {
    /** Clipboard write or clear-style request. */
    WRITE,

    /** Clipboard read/query request. */
    READ_QUERY,
}

/**
 * Adapter decision for a terminal clipboard request.
 */
enum class TerminalClipboardDecision {
    /** Request was rejected by configured policy. */
    DENIED_BY_POLICY,

    /** Clipboard reads are disabled by configured policy. */
    DENIED_READ_DISABLED,

    /** Encoded payload is malformed and cannot be safely decoded. */
    DENIED_MALFORMED_PAYLOAD,

    /** Decoded payload would exceed the configured size limit. */
    DENIED_PAYLOAD_TOO_LARGE,

    /** Request requires allowlisting, but this session was not allowlisted. */
    DENIED_NOT_ALLOWLISTED,

    /** Product host must prompt before deciding. */
    PROMPT_REQUIRED,

    /** Policy permits the request. */
    ALLOWED_BY_POLICY,
}

/**
 * Host-facing audit event for an OSC 52 terminal clipboard request.
 *
 * The encoded clipboard content is intentionally not included to avoid copying
 * sensitive data into logs, UI event queues, or telemetry.
 *
 * @property operation requested clipboard operation class.
 * @property selection OSC 52 selection designator exactly as parsed.
 * @property origin terminal session trust boundary used for policy evaluation.
 * @property encodedLength length of the base64 payload or query marker.
 * @property decodedBytes decoded byte count for write requests, or zero for
 * read/query requests and malformed payloads.
 * @property maxDecodedBytes configured decoded payload size limit.
 * @property decision adapter policy decision.
 */
data class TerminalClipboardAuditEvent(
    val operation: TerminalClipboardOperation,
    val selection: String,
    val origin: TerminalClipboardOrigin,
    val encodedLength: Int,
    val decodedBytes: Int,
    val maxDecodedBytes: Int,
    val decision: TerminalClipboardDecision,
)

/**
 * Host-facing decoded OSC 52 clipboard write request.
 *
 * This event is emitted only after the request has been parsed, size-checked,
 * decoded as UTF-8 text, and allowed by [TerminalClipboardPolicy]. It is kept
 * separate from [TerminalClipboardAuditEvent] so content-free audit streams do
 * not accidentally retain clipboard payloads.
 *
 * @property selection OSC 52 selection designator exactly as parsed.
 * @property text decoded clipboard text to write.
 * @property audit content-free audit metadata for the same request.
 */
data class TerminalClipboardWriteEvent(
    val selection: String,
    val text: String,
    val audit: TerminalClipboardAuditEvent,
)

/**
 * Host-facing decoded OSC 52 clipboard write prompt request.
 *
 * This event is emitted only after a write request has been parsed,
 * size-checked, decoded as UTF-8 text, and classified as
 * [TerminalClipboardDecision.PROMPT_REQUIRED]. It is separate from
 * [TerminalClipboardAuditEvent] so content-free audit streams remain safe for
 * logging and telemetry.
 *
 * @property selection OSC 52 selection designator exactly as parsed.
 * @property text decoded clipboard text to write if the user approves.
 * @property audit content-free audit metadata for the same request.
 */
data class TerminalClipboardPromptEvent(
    val selection: String,
    val text: String,
    val audit: TerminalClipboardAuditEvent,
)
