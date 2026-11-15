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

import io.github.ketraterm.shell.launch.TerminalShellLaunch
import io.github.ketraterm.workspace.TerminalProfile

fun main() {
    JavaConsumer.verify()
    TerminalShellLaunch.prepare(TerminalProfile("custom", "Custom", listOf("custom")), enabled = false).use { launch ->
        check(!launch.promptMarkersExpected)
        check(launch.profile.command == listOf("custom"))
        check(launch.environment(mapOf("HOST_VALUE" to "retained"))["HOST_VALUE"] == "retained")
    }
    check(runCatching { Class.forName("io.github.ketraterm.workspace.TerminalWorkspace") }.isFailure)
    check(runCatching { Class.forName("io.github.ketraterm.pty.TerminalSessions") }.isFailure)
}
