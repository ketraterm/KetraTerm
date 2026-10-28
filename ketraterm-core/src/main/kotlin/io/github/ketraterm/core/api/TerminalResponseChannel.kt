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
public interface TerminalResponseChannel : TerminalHostResponseReader {
    /**
     * The number of queued response bytes currently waiting to be read.
     */
    public val pendingResponseBytes: Int

    /**
     * Discards all queued response bytes from this channel.
     */
    public fun clearResponseBytes()

    /**
     * Publishes host permission for automatic color-scheme and resize reports.
     *
     * This also governs the immediate mode 2048 enable report. It does not
     * alter subscription state or already queued bytes, and it does not gate
     * explicit query methods: callers must continue enforcing their response
     * policy before queries. Unlike other mutations,
     * this permission publication may be called concurrently with core use.
     */
    public fun setChangeNotificationsAllowed(allowed: Boolean)

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
    public fun requestModeStatus(
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
     * Both cursor-position forms use one-based coordinates relative to the active
     * origin: top margin under DECOM, and left margin under DECOM plus DECLRMM.
     * With DECOM off they report absolute viewport coordinates. Positions restored
     * before the origin report row/column 1 rather than a non-positive coordinate.
     *
     * @param mode The DSR mode parameter.
     * @param decPrivate `true` if this is a DEC private DSR (? prefix), `false` for standard ANSI.
     */
    public fun requestDeviceStatusReport(
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
    public fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    )

    /**
     * Enqueues the active Kitty keyboard progressive-enhancement flags as a
     * parameterless `CSI ? u` query response. Core reports only flags retained
     * by its supported-mode mask.
     */
    public fun requestKittyKeyboardFlags()

    /**
     * Enqueues an allowlisted xterm key-modifier option response.
     *
     * Unsupported resources produce no response; the protocol defines no failure reply.
     * Explicit disable is reported as 65535, matching xterm's unsigned parameter.
     * Host adapters must enforce terminal-response policy before calling this API.
     *
     * @param resource The queried xterm key-modifier resource identifier.
     */
    public fun requestKeyModifierOption(resource: Int)

    /**
     * Enqueues an allowlisted XTQFMTKEYS reply using the active format resource.
     * Unknown and reserved resources stay silent; this protocol defines no failure reply.
     * Host adapters must enforce terminal-response policy before calling this API.
     */
    public fun requestKeyFormatOption(resource: Int)

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
    public fun requestRectangleChecksum(
        requestId: Int,
        page: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    )

    /**
     * Updates the recorded text-area size in pixels, excluding host padding.
     * A changed size emits a subscribed mode 2048 report when permitted. Use
     * the pixel-aware [TerminalBuffer.resize] overload when both grid and pixel
     * dimensions change so one report describes the completed resize.
     * If either dimension is nonpositive, both are recorded as unknown zero.
     *
     * @param width Width in pixels.
     * @param height Height in pixels.
     */
    public fun setWindowSizePixels(
        width: Int,
        height: Int,
    )

    /**
     * Updates the recorded window minimization state.
     *
     * @param minimized `true` if minimized, `false` otherwise.
     */
    public fun setWindowMinimized(minimized: Boolean)

    /**
     * Enqueues an allowlisted window report response using current host geometry.
     * Pixel-size query reports remain silent when the host has not supplied usable dimensions.
     * Cell pixel dimensions use the integer text-area pixel size divided by the grid
     * dimensions. Callers must enforce terminal-response permission for application queries.
     *
     * @param mode the report selector, such as [WINDOW_REPORT_PIXELS],
     * [WINDOW_REPORT_CELL_PIXELS], or [WINDOW_REPORT_GRID_CELLS]; unsupported selectors are ignored.
     */
    public fun requestWindowReport(mode: Int)

    /**
     * Enqueues the color query response for a specific 256-color palette index.
     *
     * @param index the color index to query.
     */
    public fun queryPaletteColor(index: Int)

    /**
     * Enqueues the color query response for a dynamic target (10, 11, or 12).
     *
     * @param target the target code (10 for foreground, 11 for background, 12 for cursor).
     */
    public fun queryDynamicColor(target: Int)

    /**
     * Enqueues the response for a DCS DECRQSS request.
     *
     * Only exact selectors in the core allowlist produce success responses.
     * Cursor style uses `SP q` and reports the effective shape and blink state
     * as `DCS 1 $r Ps SP q ST`, with `Ps` in 1..6. Bare `q` is unsupported.
     * Unsupported selectors enqueue the empty failure `DCS 0 $r ST`, never
     * the supplied query text. Invoke under the same synchronization as core
     * mutation; callers must enforce terminal-response permission before invoking.
     *
     * @param query the decoded selector, matched without normalization.
     */
    public fun queryStatusString(query: String)

    /**
     * Enqueues the response for a DCS XTGETTCAP request.
     *
     * Each well-formed hex name receives its own success or failure frame, in
     * request order. Only the core capability allowlist produces success.
     * Malformed batches or batches whose complete response would exceed 64 KiB
     * receive one empty failure frame without partial results.
     * This direct core API does not enforce host response permission.
     *
     * @param rawPayload the raw payload containing semicolon-separated hex capability names.
     */
    public fun queryTerminfo(rawPayload: String)

    public companion object {
        public const val DEVICE_ATTRIBUTES_PRIMARY: Int = 0
        public const val DEVICE_ATTRIBUTES_SECONDARY: Int = 1
        public const val DEVICE_ATTRIBUTES_TERTIARY: Int = 2

        public const val WINDOW_REPORT_STATE: Int = 11
        public const val WINDOW_REPORT_PIXELS: Int = 14

        /** Requests character-cell pixel dimensions as `CSI 6 ; height ; width t`. */
        public const val WINDOW_REPORT_CELL_PIXELS: Int = 16

        public const val WINDOW_REPORT_GRID_CELLS: Int = 18
        public const val WINDOW_REPORT_SCREEN_SIZE: Int = 19

        /**
         * Requests the current mode 2048 report after a host completes a deferred resize.
         * Unlike query reports, emission requires an enabled subscription and notification permission.
         * This is a host-side selector, not an additional accepted CSI window query.
         */
        public const val WINDOW_REPORT_IN_BAND_SIZE: Int = 48
    }
}
