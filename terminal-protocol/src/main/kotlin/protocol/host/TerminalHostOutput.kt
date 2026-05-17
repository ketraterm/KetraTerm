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
package com.gagik.terminal.protocol.host

/**
 * Host-bound byte sink used by terminal input encoders.
 *
 * Implementations typically forward bytes to PTY stdin or another process
 * input stream. All methods are synchronous from the caller's perspective:
 * when a method returns, the provided data has either been consumed or copied.
 *
 * Ordering across different terminal-to-host producers is the responsibility
 * of the caller. A terminal integration should serialize UI input reports
 * (keyboard, mouse, paste, focus) and parser/core responses (DSR, CPR, DA, and
 * future OSC/DCS replies) through one terminal event loop or actor before
 * writing to this sink. Concurrent calls from independent threads have no
 * ordering guarantee unless the implementation explicitly provides one.
 */
interface TerminalHostOutput {
    /**
     * Writes one unsigned byte value to the host input stream.
     *
     * @param byte byte value in the range `0..255`.
     */
    fun writeByte(byte: Int)

    /**
     * Writes a contiguous byte range to the host input stream.
     *
     * Implementations must synchronously consume or copy the provided range
     * before returning. Callers are allowed to reuse or mutate the backing array
     * immediately after this method returns.
     *
     * @param bytes backing byte array that contains the range to write.
     * @param offset first byte index to write.
     * @param length number of bytes to write.
     */
    fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /**
     * Writes an ASCII string to the host input stream.
     *
     * Implementations should reject or otherwise fail fast for non-ASCII text
     * rather than silently changing terminal wire bytes.
     *
     * @param text text whose characters must fit in the ASCII byte range.
     */
    fun writeAscii(text: String)

    /**
     * Writes a Unicode string encoded as UTF-8 to the host input stream.
     *
     * @param text text to encode and write.
     */
    fun writeUtf8(text: String)
}
