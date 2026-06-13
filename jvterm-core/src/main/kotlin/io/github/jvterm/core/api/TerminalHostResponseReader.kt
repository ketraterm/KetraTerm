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
package io.github.jvterm.core.api

/**
 * Narrow reader for terminal-to-host response bytes.
 *
 * Sessions drain this interface after parser/core mutations and forward the
 * bytes to the active connector. Implementations must synchronously copy bytes
 * into [dst] before returning.
 */
interface TerminalHostResponseReader {
    /**
     * Reads up to [length] queued response bytes into [dst].
     *
     * @return number of bytes copied, or `0` when no response is pending.
     */
    fun readResponseBytes(
        dst: ByteArray,
        offset: Int = 0,
        length: Int = dst.size - offset,
    ): Int
}
