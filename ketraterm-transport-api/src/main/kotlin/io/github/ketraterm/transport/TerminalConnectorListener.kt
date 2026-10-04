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
package io.github.ketraterm.transport

/**
 * Callback sink for terminal transport events.
 */
public interface TerminalConnectorListener {
    /**
     * Delivers bytes emitted by the remote host.
     *
     * The listener must consume the byte range synchronously before returning.
     * Connectors may reuse [bytes] after this callback returns.
     * Connectors must invoke this callback serially and in stream order for one
     * started listener.
     * If this callback throws, stop delivery and report the original exception through [onError], including cancellation.
     * Local closure can suppress that report. Never retry a failed byte range.
     *
     * @param bytes byte array containing the received data.
     * @param offset starting index of valid data in the byte array.
     * @param length number of valid bytes to consume.
     */
    public fun onBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /**
     * Reports remote transport closure.
     * Deliver all final bytes and finish their synchronous callbacks before
     * invoking this method. No bytes may follow it. The listener may close its
     * connector from this callback to release remaining transport resources.
     *
     * @param exitCode process exit code when the transport has one, otherwise
     * `null`.
     */
    public fun onClosed(exitCode: Int?)

    /**
     * Reports a remote transport failure.
     * This is terminal for the session. The listener may close its connector
     * reentrantly; cleanup must not require this callback to return first.
     *
     * @param error the transport exception or failure.
     */
    public fun onError(error: Throwable)
}
