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
package io.github.jvterm.ssh

/**
 * Factory for SSH transport connectors.
 */
object SshConnectors {
    /**
     * Creates an SSH connector for a remote PTY shell.
     *
     * @param options SSH connection and shell-channel options.
     * @return connector that can be bound to a `TerminalSession`.
     */
    @JvmStatic
    fun create(options: SshOptions): SshConnector = SshConnector(options)
}
