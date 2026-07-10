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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.workspace.TerminalProfileKind

/** Maps authoritative standalone profile categories to implemented completion capabilities. */
internal fun TerminalProfileKind.completionShellCapabilities(): TerminalShellCapabilities =
    when (this) {
        TerminalProfileKind.POWERSHELL -> TerminalShellCapabilities.POWERSHELL
        TerminalProfileKind.GIT_BASH,
        TerminalProfileKind.UBUNTU,
        TerminalProfileKind.BASH,
        TerminalProfileKind.ZSH,
        TerminalProfileKind.WSL,
        TerminalProfileKind.UNIX_SHELL,
        -> TerminalShellCapabilities.POSIX
        TerminalProfileKind.COMMAND_PROMPT,
        TerminalProfileKind.FISH,
        TerminalProfileKind.NUSHELL,
        TerminalProfileKind.DEFAULT,
        -> TerminalShellCapabilities.PLAIN
    }
