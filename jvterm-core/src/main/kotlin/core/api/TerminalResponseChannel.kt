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
package com.gagik.core.api

/**
 * Terminal-to-host response channel.
 *
 * Host applications should drain these bytes and write them to the PTY/process
 * input side. Parser/integration code should use the request methods; core owns
 * response contents that depend on core state such as cursor position.
 */
interface TerminalResponseChannel : TerminalHostResponseReader {
    val pendingResponseBytes: Int

    fun clearResponseBytes()

    fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    )

    fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    )

    fun setWindowSizePixels(
        width: Int,
        height: Int,
    )

    fun requestWindowReport(mode: Int)

    /**
     * Enqueues the color query response for a specific 256-color palette index.
     *
     * @param index the color index to query.
     */
    fun queryPaletteColor(index: Int)

    /**
     * Enqueues the color query response for a dynamic target (10, 11, or 12).
     *
     * @param target the target code (10 for foreground, 11 for background, 12 for cursor).
     */
    fun queryDynamicColor(target: Int)

    /**
     * Enqueues the response for a DCS DECRQSS request.
     *
     * @param query the target query parameter.
     */
    fun queryStatusString(query: String)

    /**
     * Enqueues the response for a DCS XTGETTCAP request.
     *
     * @param rawPayload the raw payload containing semicolon-separated hex capability names.
     */
    fun queryTerminfo(rawPayload: String)

    companion object {
        const val DEVICE_ATTRIBUTES_PRIMARY: Int = 0
        const val DEVICE_ATTRIBUTES_SECONDARY: Int = 1
        const val DEVICE_ATTRIBUTES_TERTIARY: Int = 2

        const val WINDOW_REPORT_PIXELS: Int = 14
        const val WINDOW_REPORT_GRID_CELLS: Int = 18
    }
}
