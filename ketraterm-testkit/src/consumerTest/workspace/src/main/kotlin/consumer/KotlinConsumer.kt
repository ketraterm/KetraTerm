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

import io.github.ketraterm.workspace.*

fun main() {
    JavaConsumer.verify()
    val options =
        TerminalWorkspaceOpenOptions.create {
            it.columns = 100
            it.rows = 30
        }
    val resized = options.copy { it.rows = 40 }
    check(resized.rows == 40 && options.rows == 30 && resized.columns == options.columns)
    val profile = TerminalProfile("bash", "Bash", listOf("/bin/bash"))
    val copy = profile.copy(displayName = "Renamed")
    val (id, name, command) = copy
    check(id == "bash" && name == "Renamed" && command == listOf("/bin/bash"))
    check(copy.kind == TerminalProfileKind.BASH && copy.shellEnvironment == TerminalShellEnvironment.Empty)
    val registry = TerminalProfileRegistry("Linux", emptyMap(), ":") { it.toString() == "/bin/bash" }
    check(registry.configuredProfile("/bin/bash").command.first() == "/bin/bash")
    try {
        TerminalShellEnvironment(mapOf("invalid-name" to "value"))
        error("Invalid environment accepted")
    } catch (
        _: IllegalArgumentException,
    ) {
    }
}
