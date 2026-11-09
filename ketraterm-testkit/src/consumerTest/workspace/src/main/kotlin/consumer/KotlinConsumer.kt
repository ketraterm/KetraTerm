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

import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import io.github.ketraterm.workspace.TerminalShellEnvironment
import io.github.ketraterm.workspace.TerminalWorkspace
import io.github.ketraterm.workspace.TerminalWorkspaceOpenOptions

fun main() {
    JavaConsumer.verify()
    val profile = TerminalProfile("bash", "Bash", listOf("bash"))
    check(profile.kind == TerminalProfileKind.BASH)
    check(profile.shellEnvironment == TerminalShellEnvironment.Empty)
    val options = TerminalWorkspaceOpenOptions.create { it.columns = 120 }
    check(options.columns == 120)
    check(options.copy { it.rows = 42 }.rows == 42)
    TerminalWorkspace().use { workspace ->
        check(workspace.tabSnapshot().isEmpty())
        check(workspace.selectedTab() == null)
    }
}
