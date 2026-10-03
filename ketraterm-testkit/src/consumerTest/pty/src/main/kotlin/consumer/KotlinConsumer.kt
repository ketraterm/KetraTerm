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

import io.github.ketraterm.pty.PtyConnector
import io.github.ketraterm.pty.PtyOptions

fun main() {
    JavaConsumer.verify()
    val original =
        PtyOptions.create { draft ->
            draft.command = listOf("consumer")
            draft.environment = mapOf("TERM" to "xterm-256color")
        }
    val updated =
        original.copy {
            it.columns = 101
            it.rows = 33
        }
    check(updated.command == original.command && updated.environment == original.environment)
    check(updated.columns == 101 && updated.rows == 33 && updated.inputPolicy == original.inputPolicy)
    val process = JavaConsumer.newProcess()
    PtyConnector(process).use { connector ->
        connector.resize(101, 33)
        check(process.winSize.columns == 101 && process.winSize.rows == 33)
    }
    check(!process.isAlive)
}
