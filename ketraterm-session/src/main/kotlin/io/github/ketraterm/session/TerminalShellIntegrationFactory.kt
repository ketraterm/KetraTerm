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
package io.github.ketraterm.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** Creates one shell producer before a session starts consuming output. */
public fun interface TerminalShellIntegrationFactory {
    /** Construction must not start output, subscribe to host events, or create background work. */
    public fun create(context: TerminalShellIntegrationContext): TerminalShellIntegration

    public companion object {
        /**
         * Selects a host-owned versioned projection that supports atomic conditional
         * edit admission. Publish through [commandLine] so model updates and the
         * final revision check share its guard. [state] and [promptReady] retain
         * the same ownership as in the StateFlow overload.
         */
        @JvmStatic
        @JvmOverloads
        public fun host(
            state: TerminalShellIntegrationView,
            commandLine: TerminalShellCommandLineState,
            promptReady: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
        ): TerminalShellIntegrationFactory =
            TerminalShellIntegrationFactory {
                object : TerminalShellIntegration {
                    override val state = state
                    override val promptReady = promptReady
                    override val commandLineChanges = commandLine.changes

                    override fun activeCommandLine(): TerminalShellCommandLineSnapshot? = commandLine.value

                    override fun <T> withCommandLine(action: (Long, TerminalShellCommandLineSnapshot?) -> T): T =
                        commandLine.withCommandLine(action)
                }
            }

        /**
         * Selects a host-owned shell model without installing any protocol recorder.
         *
         * The host publishes semantic prompt/command/directory updates to [state],
         * and keeps [commandLine] and [promptReady] current. Capture stable line IDs
         * through the session frame reader after the corresponding output and
         * before consuming later bytes. In particular, IDE document offsets are
         * not terminal line identities. Host callbacks must preserve that ordering.
         *
         * Each subscription signals the current editing context, including null,
         * so consumers can revalidate snapshots captured before observation starts.
         * Null editing values are authoritative. Readiness is separate from shell
         * initialization and command completion. Publish false when the prompt
         * becomes unavailable, including alternate-screen programs. A supplied
         * startup command can use this readiness through the normal session queue.
         *
         * Closing a session stops its observation without clearing [state] or
         * cancelling the host's flows. Use a distinct projection per session.
         * This StateFlow-only overload supports observation, not atomic conditional
         * edits; use [TerminalShellCommandLineState] for that capability.
         */
        @JvmStatic
        @JvmOverloads
        public fun host(
            state: TerminalShellIntegrationState,
            commandLine: StateFlow<TerminalShellCommandLineSnapshot?> =
                MutableStateFlow<TerminalShellCommandLineSnapshot?>(
                    null,
                ).asStateFlow(),
            promptReady: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
        ): TerminalShellIntegrationFactory =
            TerminalShellIntegrationFactory {
                object : TerminalShellIntegration {
                    override val state = state
                    override val promptReady = promptReady
                    private var nextRevision = 0L
                    override val commandLineChanges =
                        flow {
                            // StateFlow already suppresses equal values. Its first value,
                            // including null, must revalidate context captured before subscription.
                            commandLine.collect { emit(nextRevision++) }
                        }

                    override fun activeCommandLine(): TerminalShellCommandLineSnapshot? = commandLine.value
                }
            }
    }
}
