package com.gagik.terminal.render.api

/**
 * Color encoding kind used by [TerminalRenderAttrs].
 */
object TerminalRenderColorKind {
    /**
     * Terminal default color. The associated value must be zero.
     */
    const val DEFAULT: Int = 0

    /**
     * Indexed palette color. The associated value is in `0..255`.
     */
    const val INDEXED: Int = 1

    /**
     * Direct RGB color. The associated value is encoded as `0xRRGGBB`.
     */
    const val RGB: Int = 2
}

internal fun requireColor(name: String, kind: Int, value: Int) {
    when (kind) {
        TerminalRenderColorKind.DEFAULT -> require(value == 0) {
            "$name default color value must be zero: $value"
        }
        TerminalRenderColorKind.INDEXED -> require(value in 0..255) {
            "$name indexed color value out of range: $value"
        }
        TerminalRenderColorKind.RGB -> require(value in 0..0xFF_FFFF) {
            "$name RGB color value out of range: $value"
        }
        else -> throw IllegalArgumentException("$name color kind out of range: $kind")
    }
}
