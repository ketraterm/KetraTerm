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
package io.github.ketraterm.core.api

/**
 * Terminal-to-host response channel.
 *
 * Host applications should drain these bytes and write them to the PTY/process
 * input side. Parser/host code should use the request methods; core owns
 * response contents that depend on core state such as cursor position.
 */
interface TerminalResponseChannel : TerminalHostResponseReader {
    /**
     * The number of queued response bytes currently waiting to be read.
     */
    val pendingResponseBytes: Int

    /**
     * Discards all queued response bytes from this channel.
     */
    fun clearResponseBytes()

    /**
     * Enqueues one ANSI or DEC private DECRPM reply (`CSI [?] mode;status $ y`).
     * The explicit mode allowlist returns status 0 for unsupported queries.
     * Negative identifiers produce no response. Queries do not mutate modes or
     * invalidate rendering. Invoke under the same synchronization as core mutation.
     * Host adapters must enforce terminal-response policy before calling this API.
     *
     * @param mode nonnegative mode identifier; zero denotes an unsupported mode.
     * @param decPrivate selects DEC private modes rather than ANSI modes.
     * @param hostCapabilities implemented actions from TerminalHostModeCapability;
     * zero conservatively excludes host-dependent modes.
     * @param defaultBackarrowSendsBackspace current host default for legacy Backspace
     * before an explicit DECBKM override; false selects DEL, true selects BS.
     */
    fun requestModeStatus(
        mode: Int,
        decPrivate: Boolean,
        hostCapabilities: Int = 0,
        defaultBackarrowSendsBackspace: Boolean = false,
    )

    /**
     * Enqueues a device status report (DSR) response.
     *
     * Allowlisted requests are ANSI 5 (operating status), ANSI/private 6 (cursor
     * position), and private 996 (host color scheme). The latter replies with
     * `CSI ?997;1n` for dark or `CSI ?997;2n` for light using the host theme
     * palette, independently of application color overrides. Unsupported
     * requests stay silent; the color-scheme protocol defines no failure reply.
     * Host adapters must enforce terminal-response policy before calling this API.
     *
     * @param mode The DSR mode parameter.
     * @param decPrivate `true` if this is a DEC private DSR (? prefix), `false` for standard ANSI.
     */
    fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    )

    /**
     * Enqueues a device attributes (DA) report response.
     *
     * Response identity values are governed by the shared terminal capability
     * identity contract in `ketraterm-protocol`; DA3 remains silent by policy.
     *
     * @param kind The device attributes query type (primary, secondary, or tertiary).
     * @param parameter The request parameter/subtype (usually 0).
     */
    fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    )

    /**
     * Enqueues the active Kitty keyboard progressive-enhancement flags as a
     * parameterless `CSI ? u` query response. Core reports only flags retained
     * by its supported-mode mask.
     */
    fun requestKittyKeyboardFlags()

    /**
     * Enqueues an allowlisted xterm key-modifier option response.
     *
     * Unsupported resources produce no response; the protocol defines no failure reply.
     * Explicit disable is reported as 65535, matching xterm's unsigned parameter.
     * Host adapters must enforce terminal-response policy before calling this API.
     *
     * @param resource The queried xterm key-modifier resource identifier.
     */
    fun requestKeyModifierOption(resource: Int)

    /**
     * Enqueues an allowlisted XTQFMTKEYS reply using the active format resource.
     * Unknown and reserved resources stay silent; this protocol defines no failure reply.
     * Host adapters must enforce terminal-response policy before calling this API.
     */
    fun requestKeyFormatOption(resource: Int)

    /**
     * Enqueues a VT420 DECRQCRA response for an active-page rectangular area.
     *
     * The compatibility contract accepts page `0` (omitted) and page `1` only.
     * Other pages, malformed rectangles, and out-of-domain requests are silent.
     * The checksum uses the VT420-compatible default algorithm: erased cells and
     * wide spacer cells are omitted; printable base codepoints are reduced to
     * eight bits; and the supported VT100 video attributes are included.
     *
     * @param requestId Host-supplied request identifier echoed in the DCS response.
     * @param page DEC page number (`0` omitted or `1` active page).
     * @param top One-based top row, or `0` when omitted.
     * @param left One-based left column, or `0` when omitted.
     * @param bottom One-based bottom row, or `0` when omitted.
     * @param right One-based right column, or `0` when omitted.
     */
    fun requestRectangleChecksum(
        requestId: Int,
        page: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    )

    /**
     * Updates the recorded window size in pixels.
     *
     * @param width Width in pixels.
     * @param height Height in pixels.
     */
    fun setWindowSizePixels(
        width: Int,
        height: Int,
    )

    /**
     * Updates the recorded window minimization state.
     *
     * @param minimized `true` if minimized, `false` otherwise.
     */
    fun setWindowMinimized(minimized: Boolean)

    /**
     * Enqueues a window report response.
     *
     * @param mode The window report mode (e.g. [WINDOW_REPORT_PIXELS] or [WINDOW_REPORT_GRID_CELLS]).
     */
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
     * Only allowlisted capability names from the shared terminal capability
     * identity contract produce success responses.
     *
     * @param rawPayload the raw payload containing semicolon-separated hex capability names.
     */
    fun queryTerminfo(rawPayload: String)

    companion object {
        const val DEVICE_ATTRIBUTES_PRIMARY: Int = 0
        const val DEVICE_ATTRIBUTES_SECONDARY: Int = 1
        const val DEVICE_ATTRIBUTES_TERTIARY: Int = 2

        const val WINDOW_REPORT_STATE: Int = 11
        const val WINDOW_REPORT_PIXELS: Int = 14
        const val WINDOW_REPORT_GRID_CELLS: Int = 18
        const val WINDOW_REPORT_SCREEN_SIZE: Int = 19
    }
}
