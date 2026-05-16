package com.gagik.parser.fixture

internal object ParserEvents {
    fun writeCodepoint(codepoint: Int): String = "writeCodepoint:$codepoint"

    fun writeCluster(vararg codepoints: Int): String {
        return "writeCluster:${codepoints.size}:${codepoints.joinToString(":")}"
    }

    fun appendToPreviousCluster(codepoint: Int): String = "appendToPreviousCluster:$codepoint"
}
