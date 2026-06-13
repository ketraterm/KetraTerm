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
package io.github.jvterm.parser.ansi.dcs

import io.github.jvterm.parser.spi.TerminalCommandSink

/**
 * Routes completed DCS payloads to the appropriate [TerminalCommandSink] method.
 *
 * Recognizes two DCS sub-protocols by their payload prefix:
 * - `$q` — DECRQSS (Request Status String): the remainder is the query selector
 *   (e.g. `m` for SGR, `r` for scroll margins).
 * - `+q` — XTGETTCAP (Request Terminal Capability): the remainder is one or more
 *   semicolon-separated hex-encoded terminfo capability names.
 *
 * Payloads that overflowed the parser buffer, have zero length, or carry an
 * unrecognized prefix are silently discarded. The actual security allowlist
 * for which queries produce responses lives in the core response channel, not here;
 * this dispatcher only routes the parsed request to the sink.
 */
internal object DcsDispatcher {
    /**
     * Dispatches a completed DCS payload to the sink.
     *
     * @param sink the command sink that receives the parsed query
     * @param payload raw DCS payload bytes accumulated by the parser
     * @param length number of valid bytes in [payload]
     * @param overflowed true if the parser's payload buffer overflowed, in which
     *   case the payload is untrusted and the dispatch is skipped
     */
    fun dispatch(
        sink: TerminalCommandSink,
        payload: ByteArray,
        length: Int,
        overflowed: Boolean,
    ) {
        if (overflowed || length <= 0) {
            return
        }

        val payloadStr = payload.decodeToString(startIndex = 0, endIndex = length)
        if (payloadStr.startsWith("\$q")) {
            val query = payloadStr.substring(2)
            sink.queryStatusString(query)
        } else if (payloadStr.startsWith("+q")) {
            val rawPayload = payloadStr.substring(2)
            sink.queryTerminfo(rawPayload)
        }
    }
}
