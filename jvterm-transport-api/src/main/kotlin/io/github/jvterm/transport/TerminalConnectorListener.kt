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
 * Callback sink for terminal transport events.
 */
interface TerminalConnectorListener {
    /**
     * Delivers bytes emitted by the remote host.
     *
     * The listener must consume the byte range synchronously before returning.
     * Connectors may reuse [bytes] after this callback returns.
     * Connectors must invoke this callback serially and in stream order for one
     * started listener.
     *
     * @param bytes byte array containing the received data.
     * @param offset starting index of valid data in the byte array.
     * @param length number of valid bytes to consume.
     */
    fun onBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /**
     * Reports remote transport closure.
     *
     * @param exitCode process exit code when the transport has one, otherwise
     * `null`.
     */
    fun onClosed(exitCode: Int?)

    /**
     * Reports a remote transport failure.
     *
     * @param error the transport exception or failure.
     */
    fun onError(error: Throwable)
}
