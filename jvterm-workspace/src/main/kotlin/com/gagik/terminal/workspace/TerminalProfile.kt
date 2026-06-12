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
package com.gagik.terminal.workspace

import java.nio.file.Path

/**
 * Host-neutral terminal launch profile.
 *
 * A profile describes the process contract for one terminal workspace tab.
 * Product shells such as the standalone app and IntelliJ plugin may choose
 * their own presentation, but should share this process vocabulary to avoid
 * divergent shell discovery behavior.
 *
 * @property id stable profile identifier.
 * @property displayName user-facing profile name.
 * @property command command and arguments passed to the local PTY child process.
 * @property environment environment entries layered on top of PTY defaults.
 * @property workingDirectory initial process working directory, or `null` for
 * the platform/user default.
 * @property kind stable presentation category for host UI icons and menus.
 */
data class TerminalProfile(
    val id: String,
    val displayName: String,
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: Path? = null,
    val kind: TerminalProfileKind = TerminalProfileKind.classify(id, displayName, command),
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(displayName.isNotBlank()) { "profile displayName must not be blank" }
        require(command.isNotEmpty()) { "profile command must not be empty" }
        require(command.none { it.isEmpty() }) { "profile command elements must not be empty" }
    }
}
