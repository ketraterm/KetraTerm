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
package io.github.ketraterm.testkit

/** Identifies the active screen in a conformance snapshot. */
enum class TerminalSnapshotBufferKind {
    /** Scrollback-backed primary screen. */
    PRIMARY,

    /** Full-screen alternate buffer. */
    ALTERNATE,
}

/**
 * Cursor state detached from a short-lived render frame.
 *
 * @property column zero-based live-grid column.
 * @property row zero-based live-grid row.
 * @property visible whether terminal mode permits cursor rendering.
 * @property blinking whether cursor blinking mode is active.
 * @property shape stable render cursor-shape enum name.
 */
data class TerminalCursorSnapshot(
    val column: Int,
    val row: Int,
    val visible: Boolean,
    val blinking: Boolean,
    val shape: String,
)

/**
 * Semantic durable-mode snapshot for differential comparisons.
 *
 * Enum-backed modes use their stable protocol enum names rather than leaking
 * core implementation types into serialized conformance fixtures.
 *
 * @property insertMode whether IRM inserts rather than replaces cells.
 * @property autoWrap whether DECAWM wrapping is enabled.
 * @property applicationCursorKeys whether DECCKM cursor-key encoding is active.
 * @property applicationKeypad whether application keypad mode is active.
 * @property backarrowKeyModeExplicit whether DECBKM was explicitly selected.
 * @property backarrowKeySendsBackspace whether explicit DECBKM emits BS rather than DEL.
 * @property originMode whether cursor addressing is relative to active margins.
 * @property newLineMode whether LF also performs carriage return.
 * @property leftRightMarginMode whether DECLRMM horizontal margins are active.
 * @property reverseVideo whether DECSCNM reverse-video presentation is active.
 * @property cursorVisible whether DECTCEM cursor visibility is enabled.
 * @property cursorBlinking whether the terminal cursor is configured to blink.
 * @property bracketedPaste whether bracketed paste reporting is enabled.
 * @property focusReporting whether focus in/out reporting is enabled.
 * @property ambiguousWidthIsWide whether East Asian Ambiguous characters occupy two cells.
 * @property mouseTrackingMode stable active mouse-tracking enum name.
 * @property mouseEncodingMode stable active mouse-encoding enum name.
 * @property modifyOtherKeysMode active xterm modify-other-keys level.
 * @property formatOtherKeysMode active xterm format-other-keys layout.
 * @property kittyKeyboardFlags active Kitty progressive keyboard flags.
 * @property synchronizedOutput whether synchronized-output mode is active.
 * @property bellIsUrgent whether urgent-bell mode is active.
 * @property popOnBell whether pop-on-bell mode is active.
 */
data class TerminalModeStateSnapshot(
    val insertMode: Boolean,
    val autoWrap: Boolean,
    val applicationCursorKeys: Boolean,
    val applicationKeypad: Boolean,
    val backarrowKeyModeExplicit: Boolean,
    val backarrowKeySendsBackspace: Boolean,
    val originMode: Boolean,
    val newLineMode: Boolean,
    val leftRightMarginMode: Boolean,
    val reverseVideo: Boolean,
    val cursorVisible: Boolean,
    val cursorBlinking: Boolean,
    val bracketedPaste: Boolean,
    val focusReporting: Boolean,
    val ambiguousWidthIsWide: Boolean,
    val mouseTrackingMode: String,
    val mouseEncodingMode: String,
    val modifyOtherKeysMode: Int,
    val formatOtherKeysMode: Int,
    val kittyKeyboardFlags: Int,
    val synchronizedOutput: Boolean,
    val bellIsUrgent: Boolean,
    val popOnBell: Boolean,
)

/**
 * One public-render-ABI cell in a conformance snapshot.
 *
 * @property codepoint scalar value, or zero for blank, cluster, and wide-trailing cells.
 * @property cluster complete grapheme text for cluster-leading cells, otherwise `null`.
 * @property attributes stable primary render attribute word.
 * @property extraAttributes stable extended render attribute word.
 * @property flags stable render cell flag set.
 * @property hyperlinkId public hyperlink identifier, or zero when absent.
 */
data class TerminalCellSnapshot(
    val codepoint: Int,
    val cluster: String?,
    val attributes: Long,
    val extraAttributes: Long,
    val flags: Int,
    val hyperlinkId: Int,
)

/**
 * One retained terminal row.
 *
 * @property wrapped whether the row soft-wraps into the following row.
 * @property cells left-to-right cells at the snapshot width.
 */
data class TerminalRowSnapshot(
    val wrapped: Boolean,
    val cells: List<TerminalCellSnapshot>,
)

/**
 * Immutable byte sequence with content-based value semantics.
 *
 * Kotlin arrays compare by identity, which is unsafe for golden conformance
 * records. This type defensively copies bytes and compares their contents.
 */
class TerminalByteSequence private constructor(
    private val content: ByteArray,
) {
    /** Number of bytes in the sequence. */
    val size: Int
        get() = content.size

    /**
     * Returns a detached byte array.
     *
     * @return exact sequence contents.
     */
    fun copyBytes(): ByteArray = content.copyOf()

    /**
     * Formats bytes as uppercase, two-digit hexadecimal values without separators.
     *
     * @return canonical hexadecimal representation.
     */
    fun toHexString(): String {
        val result = CharArray(content.size * 2)
        var destination = 0
        for (byte in content) {
            val value = byte.toInt() and 0xFF
            result[destination++] = HEX_DIGITS[value ushr 4]
            result[destination++] = HEX_DIGITS[value and 0x0F]
        }
        return result.concatToString()
    }

    override fun equals(other: Any?): Boolean = other is TerminalByteSequence && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = toHexString()

    companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /** Empty byte sequence. */
        @JvmField
        val EMPTY: TerminalByteSequence = TerminalByteSequence(ByteArray(0))

        /**
         * Copies bytes into an immutable value.
         *
         * @param bytes source bytes.
         * @return content-comparable sequence.
         */
        @JvmStatic
        fun of(bytes: ByteArray): TerminalByteSequence =
            if (bytes.isEmpty()) {
                EMPTY
            } else {
                TerminalByteSequence(bytes.copyOf())
            }
    }
}

/**
 * Canonical observable result of a parser-to-core terminal replay.
 *
 * [retainedRows] contains history first and the live grid last. [liveRowStart]
 * is therefore the index of live row zero. Generation counters and internal
 * storage handles are deliberately excluded because they are implementation
 * details rather than terminal semantics.
 *
 * @property columns terminal width in cells.
 * @property visibleRows live viewport height.
 * @property historyRows number of retained rows preceding the live viewport.
 * @property discardedRows total history rows evicted by capacity limits.
 * @property liveRowStart first live-grid row within [retainedRows].
 * @property activeBuffer active primary or alternate screen.
 * @property cursor detached live-grid cursor state.
 * @property modes detached semantic mode state.
 * @property retainedRows complete retained grid from oldest history to live bottom.
 * @property windowTitle host-adapter window title metadata.
 * @property iconTitle host-adapter icon title metadata.
 * @property activeHyperlinkUri currently active OSC 8 URI, or `null`.
 * @property activeHyperlinkId currently active OSC 8 client id, or `null`.
 * @property outboundBytes cumulative terminal-to-host response bytes.
 */
data class TerminalConformanceSnapshot(
    val columns: Int,
    val visibleRows: Int,
    val historyRows: Int,
    val discardedRows: Long,
    val liveRowStart: Int,
    val activeBuffer: TerminalSnapshotBufferKind,
    val cursor: TerminalCursorSnapshot,
    val modes: TerminalModeStateSnapshot,
    val retainedRows: List<TerminalRowSnapshot>,
    val windowTitle: String,
    val iconTitle: String,
    val activeHyperlinkUri: String?,
    val activeHyperlinkId: String?,
    val outboundBytes: TerminalByteSequence,
) {
    init {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(visibleRows > 0) { "visibleRows must be > 0, was $visibleRows" }
        require(historyRows >= 0) { "historyRows must be >= 0, was $historyRows" }
        require(discardedRows >= 0L) { "discardedRows must be >= 0, was $discardedRows" }
        require(liveRowStart == historyRows) {
            "liveRowStart must equal historyRows, was liveRowStart=$liveRowStart historyRows=$historyRows"
        }
        require(retainedRows.size == historyRows + visibleRows) {
            "retained row count ${retainedRows.size} does not match history + visible rows " +
                "${historyRows + visibleRows}"
        }
        require(retainedRows.all { it.cells.size == columns }) {
            "every retained row must contain exactly $columns cells"
        }
    }
}
