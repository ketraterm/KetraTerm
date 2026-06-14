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
package io.github.jvterm.transport

/**
 * Transport-neutral terminal byte connector.
 *
 * A connector owns any transport-specific reader, watcher, or writer threads.
 * The terminal session that uses the connector owns parser/core synchronization
 * and host-bound byte ordering.
 */
interface TerminalConnector : AutoCloseable {
    /**
     * Starts delivering transport events to [listener].
     *
     * Implementations may call listener methods from transport-owned threads.
     *
     * @param listener callback sink for transport events.
     */
    fun start(listener: TerminalConnectorListener)

    /**
     * Writes a contiguous byte range to the remote host input stream.
     *
     * The range must be synchronously consumed or copied before this method
     * returns because callers may immediately reuse [bytes].
     *
     * @param bytes byte array containing data to write.
     * @param offset starting index in the byte array.
     * @param length number of bytes to write.
     */
    fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    )

    /**
     * Resizes the remote terminal transport in character cells.
     *
     * @param columns new column width count.
     * @param rows new row height count.
     */
    fun resize(
        columns: Int,
        rows: Int,
    )

    /**
     * Requests local transport shutdown.
     */
    override fun close()
}
