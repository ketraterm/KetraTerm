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
package io.github.ketraterm.examples.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.pty.PtyConnectors
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.shell.launch.TerminalShellLaunch
import io.github.ketraterm.workspace.TerminalProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.swing.Swing
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.WindowConstants

/** Runs one host-owned shell. Pass the executable and its arguments after `--args`. */
fun main(args: Array<String>) =
    runBlocking {
        val profile = hostProfile(args.toList())
        withHostSession(profile) { session ->
            val closed = CompletableDeferred<Unit>()
            val bound = CompletableDeferred<Unit>()
            var pane: HostTerminalPane? = null
            var window: JFrame? = null
            try {
                withContext(Dispatchers.Swing) {
                    val content = HostTerminalPane(session, profile)
                    pane = content
                    window =
                        JFrame("KetraTerm host integration").apply {
                            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                            contentPane = content.component
                            addWindowListener(
                                object : WindowAdapter() {
                                    override fun windowClosing(event: WindowEvent) {
                                        closed.complete(Unit)
                                    }
                                },
                            )
                            pack()
                            isLocationByPlatform = true
                            isVisible = true
                        }
                    content.terminal.bind(session) { failure ->
                        if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                    }
                }
                if (!startBoundSession(session, bound, closed)) return@withHostSession
                withContext(Dispatchers.Swing) { pane?.terminal?.requestFocusInWindow() }
                closed.await()
            } finally {
                withContext(NonCancellable + Dispatchers.Swing) {
                    try {
                        pane?.close()
                    } finally {
                        window?.dispose()
                    }
                }
            }
        }
    }

internal fun hostProfile(command: List<String>): TerminalProfile =
    TerminalProfile(
        id = "host-example",
        displayName = "Host shell",
        command =
            command.ifEmpty {
                if (System.getProperty("os.name").startsWith("Windows")) {
                    listOf("powershell.exe", "-NoLogo")
                } else {
                    PtyOptions.defaultCommand()
                }
            },
        workingDirectory = Path.of(System.getProperty("user.dir")),
    )

/** Owns the launch, connector and session. The caller owns only its views. */
internal suspend fun withHostSession(
    profile: TerminalProfile,
    action: suspend (TerminalSession) -> Unit,
) {
    withContext(Dispatchers.IO) {
        TerminalShellLaunch.prepare(profile).use { launch ->
            val connector =
                PtyConnectors.create(
                    launch.profile.command,
                    launch.environment(PtyOptions.defaultEnvironment()),
                    launch.profile.workingDirectory,
                    columns = 100,
                    rows = 28,
                )
            val session =
                try {
                    TerminalSession.create(
                        TerminalBuffers.create(100, 28, maxHistory = 5_000),
                        connector,
                        inputPolicy = PtyOptions.defaultInputPolicy(),
                        shellIntegration = launch.shellIntegration,
                    )
                } catch (failure: Throwable) {
                    try {
                        connector.close()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                    throw failure
                }
            session.use {
                launch.attach(session)
                action(session)
            }
        }
    }
}

internal suspend fun startBoundSession(
    session: TerminalSession,
    bound: Deferred<Unit>,
    closed: Deferred<Unit>,
): Boolean =
    supervisorScope {
        val ready =
            select {
                closed.onAwait { false }
                bound.onAwait { true }
            }
        if (!ready || closed.isCompleted) return@supervisorScope false
        val startup = async(Dispatchers.IO) { session.start() }
        try {
            select {
                closed.onAwait { false }
                startup.onAwait { true }
            }
        } finally {
            if (!startup.isCompleted) {
                session.requestClose()
                startup.cancel()
            }
        }
    }
