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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.config.KetraTermConfig
import io.github.ketraterm.app.config.KetraTermConfigManager
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalWorkspace
import io.github.ketraterm.workspace.TerminalWorkspaceListener
import io.github.ketraterm.workspace.TerminalWorkspaceOpenOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.*
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses real product panes and a controlled connector; the frame requires a graphical environment. */
@Tag("native")
class TabManagerStartupTest {
    @Test
    fun `viewport submission failure releases startup and queued workspace shutdown`(
        @TempDir directory: Path,
    ) {
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(KetraTermConfig(cursorBlinkMillis = 0, smartSuggestionsEnabled = false, showForegroundProcessName = false))
        val settings = KetraTermSettings(configManager)
        val profile = TerminalProfile("startup", "Startup", listOf("unused"), workingDirectory = directory)
        val rejected = IllegalStateException("Resize submission rejected")
        val reported = CompletableFuture<Throwable?>()
        val opened = CompletableFuture<Boolean>()
        val closed = CompletableFuture<Unit>()
        val connectorClosed = CompletableFuture<Unit>()
        val outputStarted = CompletableFuture<Unit>()
        val frame = onEdt { JFrame() }
        var manager: TabManager? = null
        try {
            manager =
                onEdt {
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        JPanel(CardLayout()),
                        settings,
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            workspace(listener) { options ->
                                idleSession(
                                    options,
                                    onClose = { connectorClosed.complete(Unit) },
                                    onStart = { outputStarted.complete(Unit) },
                                )
                            }
                        },
                    )
                }
            onEdt {
                assertTrue(
                    manager.openTab(
                        profile,
                        beforeStart = { complete ->
                            synchronizeInitialViewport(
                                isCurrent = { true },
                                resize = { throw rejected },
                                onComplete = { failure ->
                                    reported.complete(failure)
                                    // Shutdown must queue behind the blocked startup worker and still finish.
                                    manager.closeAllTabsWithoutConfirmation { result ->
                                        result.fold(closed::complete, closed::completeExceptionally)
                                    }
                                    complete(failure)
                                },
                            )
                        },
                        onComplete = opened::complete,
                    ),
                )
            }
            assertTrue(reported.get(10, TimeUnit.SECONDS) === rejected)
            assertFalse(opened.get(10, TimeUnit.SECONDS))
            closed.get(10, TimeUnit.SECONDS)
            connectorClosed.get(10, TimeUnit.SECONDS)
            assertFalse(outputStarted.isDone)
            onEdt { assertEquals(null, manager.selectedPane) }
        } finally {
            close(manager, frame, closed)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pane installation uses the current process name preference`(
        enabled: Boolean,
        @TempDir directory: Path,
    ) {
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(KetraTermConfig(cursorBlinkMillis = 0, smartSuggestionsEnabled = false, showForegroundProcessName = !enabled))
        val settings = KetraTermSettings(configManager)
        val profile = TerminalProfile("startup", "Startup", listOf("unused"), workingDirectory = directory)
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val opened = CompletableFuture<Boolean>()
        val frame = onEdt { JFrame() }
        var manager: TabManager? = null
        try {
            manager =
                onEdt {
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        JPanel(CardLayout()),
                        settings,
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            workspace(listener) { options ->
                                entered.complete(Unit)
                                release.get(10, TimeUnit.SECONDS)
                                idleSession(options)
                            }
                        },
                    ).also { assertTrue(it.openTab(profile, onComplete = opened::complete)) }
                }
            entered.get(10, TimeUnit.SECONDS)
            settings.update(settings.config.copy(showForegroundProcessName = enabled))
            // The settings notification is queued before this EDT barrier; no pane exists yet.
            onEdt { assertEquals(null, manager.selectedPane) }
            release.complete(Unit)
            assertTrue(opened.get(10, TimeUnit.SECONDS))
            onEdt { assertEquals(enabled, requireNotNull(manager.selectedPane).tab.showForegroundProcessName) }
        } finally {
            release.complete(Unit)
            close(manager, frame)
        }
    }

    @Test
    fun `closing an installed pane cancels pending startup without starting output`(
        @TempDir directory: Path,
    ) {
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(KetraTermConfig(cursorBlinkMillis = 0, smartSuggestionsEnabled = false, showForegroundProcessName = false))
        val settings = KetraTermSettings(configManager)
        val profile = TerminalProfile("startup", "Startup", listOf("unused"), workingDirectory = directory)
        val ready = CompletableFuture<(Throwable?) -> Unit>()
        val opened = CompletableFuture<Boolean>()
        val outputStarted = CompletableFuture<Unit>()
        val connectorClosed = CompletableFuture<Unit>()
        val frame = onEdt { JFrame() }
        var manager: TabManager? = null
        try {
            manager =
                onEdt {
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        JPanel(CardLayout()),
                        settings,
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            workspace(listener) { options ->
                                idleSession(
                                    options,
                                    onStart = { outputStarted.complete(Unit) },
                                    onClose = { connectorClosed.complete(Unit) },
                                )
                            }
                        },
                    ).also { assertTrue(it.openTab(profile, beforeStart = { ready.complete(it) }, onComplete = opened::complete)) }
                }
            val complete = ready.get(10, TimeUnit.SECONDS)
            onEdt {
                manager.closePane(requireNotNull(manager.selectedPane))
                complete(null)
            }
            assertFalse(opened.get(10, TimeUnit.SECONDS))
            connectorClosed.get(10, TimeUnit.SECONDS)
            assertFalse(outputStarted.isDone, "Completed cancellation must never start the connector")
            onEdt { assertEquals(null, manager.selectedPane) }
        } finally {
            if (ready.isDone) onEdt { ready.get(10, TimeUnit.SECONDS)(null) }
            close(manager, frame)
        }
    }

    @ParameterizedTest
    @CsvSource("110, 32", "133, 37")
    fun `initial window waits for its pane and packs the configured grid`(
        columns: Int,
        rows: Int,
        @TempDir directory: Path,
    ) {
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(
            KetraTermConfig(
                columns = columns,
                rows = rows,
                cursorBlinkMillis = 0,
                smartSuggestionsEnabled = false,
                showForegroundProcessName = false,
            ),
        )
        val settings = KetraTermSettings(configManager)
        val profile = TerminalProfile("startup", "Startup", listOf("unused"), workingDirectory = directory)
        val entered = CompletableFuture<TerminalWorkspaceOpenOptions>()
        val release = CompletableFuture<Unit>()
        val shown = CompletableFuture<Dimension>()
        val outputStarted = CompletableFuture<Boolean>()
        val frame =
            onEdt {
                object : JFrame() {
                    override fun setVisible(visible: Boolean) {
                        // Capture the packed bounds without involving the native window manager.
                        if (visible) shown.complete(size) else super.setVisible(false)
                    }
                }.apply { minimumSize = Dimension(720, 420) }
            }
        var manager: TabManager? = null
        try {
            manager =
                onEdt {
                    val content = JPanel(CardLayout())
                    frame.contentPane = content
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        content,
                        settings,
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            workspace(listener) { options ->
                                entered.complete(options)
                                release.get(10, TimeUnit.SECONDS)
                                idleSession(options) { outputStarted.complete(shown.isDone) }
                            }
                        },
                    ).also { Window(frame, it).openInitialTab(profile) }
                }
            val options = entered.get(10, TimeUnit.SECONDS)
            assertEquals(columns, options.columns)
            assertEquals(rows, options.rows)
            onEdt {
                assertFalse(shown.isDone, "The empty window must not be shown while the pane is still starting")
                assertFalse(frame.isDisplayable, "Packing must wait until the configured pane is installed")
            }
            release.complete(Unit)
            val packed = shown.get(10, TimeUnit.SECONDS)
            assertTrue(outputStarted.get(10, TimeUnit.SECONDS), "Shell output must start after initial window packing")
            onEdt {
                assertEquals(frame.preferredSize, packed)
                val pane = requireNotNull(manager.selectedPane)
                assertEquals(Dimension(columns, rows), pane.terminal.visibleGridSize())
                assertTrue(packed.width > frame.minimumSize.width)
                assertTrue(packed.height > frame.minimumSize.height)
            }
        } finally {
            release.complete(Unit)
            close(manager, frame)
        }
    }

    @Test
    fun `cancelled initial startup disposes its hidden window without showing it`(
        @TempDir directory: Path,
    ) {
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(KetraTermConfig(cursorBlinkMillis = 0, smartSuggestionsEnabled = false, showForegroundProcessName = false))
        val settings = KetraTermSettings(configManager)
        val profile = TerminalProfile("startup", "Startup", listOf("unused"), workingDirectory = directory)
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val shown = CompletableFuture<Unit>()
        val disposed = CompletableFuture<Unit>()
        val closed = CompletableFuture<Unit>()
        val frame =
            onEdt {
                object : JFrame() {
                    override fun setVisible(visible: Boolean) {
                        if (visible) shown.complete(Unit) else super.setVisible(false)
                    }

                    override fun dispose() {
                        super.dispose()
                        disposed.complete(Unit)
                    }
                }
            }
        var manager: TabManager? = null
        try {
            manager =
                onEdt {
                    val content = JPanel(CardLayout())
                    frame.contentPane = content
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        content,
                        settings,
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            workspace(listener) { options ->
                                entered.complete(Unit)
                                release.get(10, TimeUnit.SECONDS)
                                idleSession(options)
                            }
                        },
                    ).also { Window(frame, it).openInitialTab(profile) }
                }
            entered.get(10, TimeUnit.SECONDS)
            onEdt { manager.closeAllTabsWithoutConfirmation { result -> result.fold(closed::complete, closed::completeExceptionally) } }
            release.complete(Unit)
            closed.get(10, TimeUnit.SECONDS)
            disposed.get(10, TimeUnit.SECONDS)
            onEdt {
                assertFalse(shown.isDone, "The completed cancellation must not resurrect the window")
                assertFalse(frame.isDisplayable)
            }
        } finally {
            release.complete(Unit)
            close(manager, frame, closed)
        }
    }

    @Test
    fun `settings revocation during initial binding remains effective when startup completes`(
        @TempDir directory: Path,
    ) {
        val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
        configManager.save(
            KetraTermConfig(
                cursorBlinkMillis = 0,
                smartSuggestionsEnabled = false,
                showForegroundProcessName = false,
                titlePermission = TerminalTitlePermission.ALLOW,
            ),
        )
        val settings = KetraTermSettings(configManager)
        val profile = TerminalProfile("startup", "Startup", listOf("unused"), workingDirectory = directory)
        val releaseAdmission = CountDownLatch(1)
        val gateTask = CompletableFuture<FutureTask<Unit>>()
        val paneInstalled = CompletableFuture<Unit>()
        val opened = CompletableFuture<Boolean>()
        val titles = CopyOnWriteArrayList<String>()
        val frame = onEdt { JFrame() }
        var manager: TabManager? = null
        try {
            manager =
                onEdt {
                    val content =
                        JPanel(CardLayout()).apply {
                            addContainerListener(
                                object : ContainerAdapter() {
                                    override fun componentAdded(event: ContainerEvent) {
                                        paneInstalled.complete(Unit)
                                    }
                                },
                            )
                        }
                    TabManager(
                        frame,
                        TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }),
                        content,
                        settings,
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            workspace(listener) { options ->
                                val connector =
                                    object : TerminalConnector {
                                        override fun start(listener: TerminalConnectorListener) {
                                            check(paneInstalled.isDone) { "Output started before pane installation" }
                                            val bytes = "\u001B]2;revoked-title\u0007".encodeToByteArray()
                                            listener.onBytes(bytes, 0, bytes.size)
                                        }

                                        override fun resize(
                                            columns: Int,
                                            rows: Int,
                                        ) = Unit

                                        override fun write(
                                            bytes: ByteArray,
                                            offset: Int,
                                            length: Int,
                                        ) = Unit

                                        override fun close() = Unit
                                    }
                                val session =
                                    TerminalSession.create(
                                        TerminalBuffers.create(options.columns, options.rows),
                                        connector,
                                        hostEvents =
                                            object : HostEventSink by HostEventSink.NONE {
                                                override fun windowTitleChanged(title: String) {
                                                    titles += title
                                                }
                                            },
                                        hostPolicy = options.hostPolicy,
                                    )
                                val entered = CountDownLatch(1)
                                val gate =
                                    FutureTask {
                                        session.withMutationAdmission({ true }) {
                                            entered.countDown()
                                            check(releaseAdmission.await(10, TimeUnit.SECONDS)) { "Admission gate was not released" }
                                        }
                                        Unit
                                    }
                                gateTask.complete(gate)
                                Thread
                                    .ofPlatform()
                                    .daemon()
                                    .name("startup-admission")
                                    .start(gate)
                                check(entered.await(10, TimeUnit.SECONDS)) { "Admission gate was not entered" }
                                session
                            }
                        },
                    ).also { assertTrue(it.openTab(profile) { success -> opened.complete(success) }) }
                }
            paneInstalled.get(10, TimeUnit.SECONDS)
            // update returns after the manager's settings listener has queued the denial on the EDT.
            settings.update(settings.config.copy(titlePermission = TerminalTitlePermission.DENY))
            assertFalse(opened.isDone, "Startup must still await the held initial binding")
            releaseAdmission.countDown()
            gateTask.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            assertTrue(opened.get(10, TimeUnit.SECONDS))
            assertEquals(emptyList(), titles, "Binding completion must not restore the startup permission snapshot")
        } finally {
            releaseAdmission.countDown()
            if (gateTask.isDone) gateTask.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            val closed = CompletableFuture<Unit>()
            onEdt {
                try {
                    val current = manager
                    if (current == null) {
                        closed.complete(Unit)
                    } else {
                        current.closeAllTabsWithoutConfirmation { result -> result.fold(closed::complete, closed::completeExceptionally) }
                    }
                } finally {
                    frame.dispose()
                }
            }
            closed.get(10, TimeUnit.SECONDS)
        }
    }

    private fun workspace(
        listener: TerminalWorkspaceListener,
        createSession: (TerminalWorkspaceOpenOptions) -> TerminalSession,
    ): TerminalWorkspace {
        val factoryType = Class.forName("io.github.ketraterm.workspace.TerminalWorkspaceSessionFactory")
        val factory =
            Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, method, args ->
                check(method.name == "create") { "Unexpected session factory call: ${method.name}" }
                createSession(requireNotNull(args)[1] as TerminalWorkspaceOpenOptions)
            }
        return TerminalWorkspace::class.java
            .getDeclaredConstructor(TerminalWorkspaceListener::class.java, factoryType, CoroutineDispatcher::class.java)
            .newInstance(listener, factory, Dispatchers.Default) as TerminalWorkspace
    }

    private fun idleSession(
        options: TerminalWorkspaceOpenOptions,
        onClose: () -> Unit = {},
        onStart: () -> Unit = {},
    ): TerminalSession =
        TerminalSession.create(
            TerminalBuffers.create(options.columns, options.rows),
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) = onStart()

                override fun resize(
                    columns: Int,
                    rows: Int,
                ) = Unit

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = Unit

                override fun close() = onClose()
            },
            hostPolicy = options.hostPolicy,
        )

    private fun close(
        manager: TabManager?,
        frame: JFrame,
        closed: CompletableFuture<Unit> = CompletableFuture(),
    ) {
        onEdt {
            try {
                if (manager == null) {
                    closed.complete(Unit)
                } else {
                    manager.closeAllTabsWithoutConfirmation { result -> result.fold(closed::complete, closed::completeExceptionally) }
                }
            } finally {
                frame.dispose()
            }
        }
        closed.get(10, TimeUnit.SECONDS)
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
