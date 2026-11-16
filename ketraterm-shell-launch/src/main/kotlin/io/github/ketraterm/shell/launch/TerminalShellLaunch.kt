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
package io.github.ketraterm.shell.launch

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.workspace.ShellCommandInventoryFiles
import io.github.ketraterm.workspace.ShellIntegrationLaunch
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalShellIntegrationBootstrap
import java.nio.file.Path

/**
 * Prepared shell hooks for one host-owned process and session.
 *
 * Preparation can write startup scripts. Call it off the UI thread before process creation.
 * Pass [profile] to the process launcher and [shellIntegration] to the session factory.
 * Call [attach] before starting the session. Close this handle if startup fails.
 * Close the process and session before closing this handle during normal disposal.
 * This handle never starts or closes a process or a session.
 *
 * Generated scripts are reusable cache files owned by the host's script directory.
 * Closing this handle removes only its private command inventory mailbox.
 */
public class TerminalShellLaunch private constructor(
    private val launch: ShellIntegrationLaunch,
    private val inventory: ShellCommandInventoryFiles?,
) : AutoCloseable {
    /**
     * Detached launch profile with shell hooks and initial environment values.
     * Environment resolution uses the current JVM's environment. Use [environment] for host-provided inheritance.
     */
    public val profile: TerminalProfile get() = launch.profile

    /** Whether this launch installed prompt hooks. Shell readiness is observed separately. */
    public val promptMarkersExpected: Boolean get() = launch.promptMarkersExpected

    /** Use this factory for exactly one session that receives this process's output. */
    public val shellIntegration: TerminalShellIntegrationFactory =
        OscShellIntegration.configured(promptMarkersExpected, inventory?.input)

    /**
     * Resolves the launch environment against [inherited], with case-insensitive keys on Windows.
     * Selected shell variables override profile entries, which override inherited entries.
     * The PATH prefix applies after these overrides. Calls do not change the supplied map or prepared profile.
     * Zsh hooks temporarily redirect ZDOTDIR and preserve the selected startup directory.
     */
    public fun environment(inherited: Map<String, String>): Map<String, String> = launch.environment(inherited)

    private var attached = false
    private var closed = false

    /**
     * Starts inventory observation for [session]. Call once, before [TerminalSession.start].
     * Session closure also releases the mailbox. The host retains responsibility for startup failure.
     * Throws [IllegalStateException] after attachment, closure, or session startup.
     */
    @Synchronized
    public fun attach(session: TerminalSession) {
        check(!closed && !attached) { "Shell launch is already attached or closed" }
        check(session.state.value == TerminalSessionState.Created) { "Attach shell launch before session startup" }
        inventory?.observe(session)
        attached = true
    }

    /** Releases this launch's mailbox. Repeated calls have no effect. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        inventory?.close()
    }

    public companion object {
        /**
         * Prepares one launch without creating a process, session, or workspace.
         *
         * [scriptDirectory] owns reusable startup scripts and must be local to the launched shell.
         * A null directory uses KetraTerm's default script cache.
         * [enabled] controls shell hooks. Explicit script arguments remain unchanged.
         * Unsupported shells return [promptMarkersExpected] as false.
         * Startup commands require supported interactive hooks and otherwise fail before process creation.
         * The caller must not mutate [profile] collections during this call.
         */
        @JvmStatic
        @JvmOverloads
        public fun prepare(
            profile: TerminalProfile,
            enabled: Boolean = true,
            scriptDirectory: Path? = null,
        ): TerminalShellLaunch {
            val detached =
                profile.copy(
                    command = profile.command.toList(),
                    environment = profile.environment.toMap(),
                    shellEnvironment = profile.shellEnvironment.copy(variables = profile.shellEnvironment.variables.toMap()),
                )
            val inventory =
                if (enabled && TerminalShellIntegrationBootstrap.supportsCommandInventory(detached)) {
                    ShellCommandInventoryFiles()
                } else {
                    null
                }
            try {
                val launch =
                    if (scriptDirectory == null) {
                        TerminalShellIntegrationBootstrap.apply(detached, enabled, commandInventoryDirectory = inventory?.directory)
                    } else {
                        TerminalShellIntegrationBootstrap.apply(detached, enabled, scriptDirectory, inventory?.directory)
                    }
                val retained = inventory?.takeIf { launch.promptMarkersExpected }
                if (retained == null) inventory?.close()
                return TerminalShellLaunch(launch, retained)
            } catch (failure: Throwable) {
                inventory?.close()
                throw failure
            }
        }
    }
}
