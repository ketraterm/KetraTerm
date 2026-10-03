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
import io.github.ketraterm.session.TerminalSession

fun main() {
    JavaConsumer.verify()
    val connector = JavaConsumer.Connector()
    val buffer = TerminalBuffers.create(80, 3, 0)
    TerminalSession.create(buffer, buffer, connector, shellIntegration = JavaConsumer.factory()).use { session ->
        session.start(80, 3)
        val bytes = "\u001b]7;file:///tmp\u0007\u001b]133;A\u0007$ \u001b]133;B\u0007git status".encodeToByteArray()
        // Chunking crosses OSC terminators and printable ingress without relying on timers.
        for (index in bytes.indices) connector.listener.onBytes(bytes, index, 1)
        check(session.currentWorkingDirectoryUri() == "file:///tmp")
        val command = checkNotNull(session.activeShellCommandLine())
        check(command.commandText == "git status" && command.cursorOffset == 10)
    }
    check(connector.closes == 1)
}
