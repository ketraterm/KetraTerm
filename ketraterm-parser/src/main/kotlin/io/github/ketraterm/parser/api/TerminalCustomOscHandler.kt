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
package io.github.ketraterm.parser.api

/**
 * Handles complete OSC commands that the parser does not support.
 *
 * The host owns this handler and its protocol, decoding, permissions, and resource cleanup.
 * The parser retains the handler for its lifetime. It never closes it.
 * Supported command numbers never reach this handler, including malformed or denied requests.
 * Future parser versions can support more commands and thus remove them from this route.
 *
 * The callback runs synchronously on the parser caller's thread, after preceding commands and before later bytes.
 * With a session, it holds mutation serialization. It may read a session frame and update host metadata.
 * It must return promptly, without waiting for UI work or calling mutating session APIs, including close.
 * Reentry into any parser operation throws [IllegalStateException] before mutation.
 * Exceptions, including cancellation, propagate unchanged and stop the current input call.
 * The owner must stop that stream after failure.
 * A session connector reports the failure through its listener, which closes the session and records the cause.
 */
public fun interface TerminalCustomOscHandler {
    /**
     * Borrows the body after the first semicolon, excluding the command number and terminator.
     *
     * The array is read-only and valid only during this call. Copy the specified range to retain it.
     * Offsets are zero-based byte indices; [length] can be zero. No decoding or UTF-8 validation occurs.
     * The host must validate the body before acting on it.
     *
     * The complete envelope includes decimal digits and the separator. Its default limit is 4096 bytes.
     * [TerminalParsers.create] can set a different limit. The command header retains its separate 4096-byte ceiling.
     * Overflow rejects the entire command. Malformed numbers, cancelled strings, and incomplete EOF input produce no callback.
     * BEL and ESC backslash terminate OSC. Ordinary C0 controls and DEL are ignored by the existing string rules.
     * Raw C1 bytes remain payload bytes in the UTF-8 parser.
     *
     * @param command nonnegative decimal command number, at most [Int.MAX_VALUE].
     * @param payload borrowed parser storage; only the specified range belongs to this command.
     * @param offset first body byte in [payload].
     * @param length number of body bytes.
     */
    public fun handle(
        command: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
    )
}
