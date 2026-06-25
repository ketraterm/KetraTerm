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
package io.github.ketraterm.protocol

/**
 * Semantic markers emitted by FinalTerm-style OSC 133 shell integration.
 *
 * These values describe shell lifecycle boundaries only. They do not imply
 * command history storage, prompt rendering, row anchoring, or UI decorations.
 */
enum class ShellIntegrationMarker {
    /**
     * The shell is about to print a prompt (`OSC 133 ; A ST`).
     */
    PROMPT_START,

    /**
     * The shell has finished printing the prompt and user input begins (`OSC 133 ; B ST`).
     */
    PROMPT_END,

    /**
     * The command line has been accepted and command output is about to begin (`OSC 133 ; C ST`).
     */
    COMMAND_START,

    /**
     * Command execution has finished (`OSC 133 ; D [; exitCode] ST`).
     */
    COMMAND_FINISHED,
}

/**
 * Host-facing OSC 133 shell integration event.
 *
 * @property marker shell lifecycle marker.
 * @property exitCode optional process exit status for [ShellIntegrationMarker.COMMAND_FINISHED].
 */
data class ShellIntegrationEvent(
    val marker: ShellIntegrationMarker,
    val exitCode: Int? = null,
)
