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

import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.NotificationLevel

fun main() {
    JavaConsumer.verify()
    val output = StringBuilder()
    val sink = JavaConsumer.sink(output)
    val parser = TerminalParsers.create(sink)
    parser.accept("Kotlin".encodeToByteArray())
    parser.endOfInput()
    sink.showNotification("title", "!", NotificationLevel.INFO)
    check(output.toString() == "Kotlin!")
    val custom =
        TerminalParsers.create(sink, { 0 }) { command, payload, offset, length ->
            check(command == 1341)
            output.append(payload.decodeToString(offset, offset + length))
        }
    custom.accept("\u001b]1341;custom\u0007".encodeToByteArray())
    check(output.toString() == "Kotlin!custom")
}
