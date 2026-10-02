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
package consumer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalModeBits
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.parser.api.TerminalParsers

fun main() {
    JavaConsumer.verify()
    val policy = HostPolicy(maxHyperlinkEntries = 8).copy(notificationPolicy = HostControlPolicy.DENY)
    check(policy.maxHyperlinkEntries == 8 && policy.notificationPolicy == HostControlPolicy.DENY)
    val buffer = TerminalBuffers.create(12, 2, 0)
    val parser = TerminalParsers.create(HostCommandAdapter(buffer, hostPolicy = policy))
    parser.accept("Kotlin\u001b[?7l\u001b[?2004h\u001b[?1004h".encodeToByteArray())
    parser.endOfInput()
    check(buffer.getLineAsString(0).trimEnd() == "Kotlin")
    val modes = buffer.getInputModeBits()
    check(!TerminalModeBits.hasFlag(modes, TerminalModeBits.AUTO_WRAP))
    check(TerminalModeBits.hasFlag(modes, TerminalModeBits.BRACKETED_PASTE))
    check(TerminalModeBits.hasFlag(modes, TerminalModeBits.FOCUS_REPORTING))
    // Inlined constants must retain their meaning when the client is not recompiled.
    for (
    (name, value) in
    listOf(
        "AUTO_WRAP" to TerminalModeBits.AUTO_WRAP,
        "CURSOR_VISIBLE" to TerminalModeBits.CURSOR_VISIBLE,
        "BRACKETED_PASTE" to TerminalModeBits.BRACKETED_PASTE,
        "FOCUS_REPORTING" to TerminalModeBits.FOCUS_REPORTING,
    )
    ) {
        check(TerminalModeBits::class.java.getField(name).getLong(null) == value) { "Inlined mode value changed: $name" }
    }
}
