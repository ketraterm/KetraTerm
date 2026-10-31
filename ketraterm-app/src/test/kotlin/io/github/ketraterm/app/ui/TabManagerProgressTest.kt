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
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.workspace.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.*
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives actual OSC bytes through session/workspace while native progress publication is recorded. */
@Tag("native")
class TabManagerProgressTest {
    @Test
    fun `application progress decorates tab and window and survives title changes until clear`(
        @TempDir directory: Path,
    ) {
        Fixture(directory).use { fixture ->
            val pane = fixture.open()
            fixture.report(pane.tab, 1, 50)
            onEdt {
                assertEquals("[50%] Progress", fixture.bar.selectedDisplayTitle())
                assertEquals("[50%] Progress", fixture.frame.title)
                assertEquals("Progress", fixture.bar.selectedTitle())
                pane.tab.customTitle = "Renamed"
            }
            onEdt {
                assertEquals("[50%] Renamed", fixture.frame.title)
                assertEquals("Renamed", pane.tab.title)
                assertEquals("Renamed", fixture.bar.selectedTitle())
            }
            fixture.report(pane.tab, 2, 30)
            onEdt { assertEquals("[Error 30%] Renamed", fixture.frame.title) }
            fixture.report(pane.tab, 4, 40)
            onEdt { assertEquals("[Warning 40%] Renamed", fixture.frame.title) }
            fixture.report(pane.tab, 3, 0)
            onEdt { assertEquals("[Working…] Renamed", fixture.frame.title) }
            fixture.report(pane.tab, 0, 0)
            onEdt {
                assertEquals("Renamed", fixture.frame.title)
                assertEquals(TerminalProgressState.HIDDEN, fixture.published.last().state)
            }
            fixture.report(pane.tab, 1, 75)
            fixture.emitAndAwait(pane.tab, TerminalProgressState.HIDDEN, 0, "\u001Bc")
            onEdt { assertEquals("Renamed", fixture.frame.title) }
        }
    }

    @Test
    fun `background progress is retained and selection controls window presentation`(
        @TempDir directory: Path,
    ) {
        Fixture(directory).use { fixture ->
            val first = fixture.open()
            val second = fixture.open()
            fixture.report(first.tab, 1, 60)
            onEdt {
                assertEquals("Progress", fixture.frame.title)
                assertEquals(TerminalProgressState.HIDDEN, fixture.published.last().state)
                fixture.manager.selectTab(first.tab.id)
                assertEquals("[60%] Progress", fixture.bar.selectedDisplayTitle())
                assertEquals("[60%] Progress", fixture.frame.title)
                assertEquals(TerminalProgressState.NORMAL, fixture.published.last().state)
                assertEquals(60, fixture.published.last().percentage)
                fixture.manager.selectTab(second.tab.id)
                assertEquals("Progress", fixture.frame.title)
                assertEquals(TerminalProgressState.HIDDEN, fixture.published.last().state)
                fixture.manager.selectTab(first.tab.id)
                assertEquals("[60%] Progress", fixture.frame.title)
                fixture.manager.closePane(first)
                assertEquals("Progress", fixture.frame.title)
                assertEquals(TerminalProgressState.HIDDEN, fixture.published.last().state)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `queued progress reads latest state and cannot restore closed presentation`(
        closeBeforeDelivery: Boolean,
        @TempDir directory: Path,
    ) {
        Fixture(directory).use { fixture ->
            val pane = fixture.open()
            val entered = CompletableFuture<Unit>()
            val release = CompletableFuture<Unit>()
            val gate =
                FutureTask {
                    entered.complete(Unit)
                    release.get(10, TimeUnit.SECONDS)
                    if (closeBeforeDelivery) fixture.manager.closePane(pane)
                }
            val before = onEdt { fixture.published.size }
            SwingUtilities.invokeLater(gate)
            try {
                entered.get(10, TimeUnit.SECONDS)
                fixture.report(pane.tab, 1, 10, drain = false)
                fixture.report(pane.tab, 1, 90, drain = false)
            } finally {
                release.complete(Unit)
            }
            gate.get(10, TimeUnit.SECONDS)
            onEdt {
                if (closeBeforeDelivery) {
                    assertEquals(null, fixture.bar.selectedDisplayTitle())
                    assertEquals(Chrome.APP_TITLE, fixture.frame.title)
                    assertEquals(TerminalProgressState.HIDDEN, fixture.published.last().state)
                } else {
                    assertEquals("[90%] Progress", fixture.bar.selectedDisplayTitle())
                    assertEquals("[90%] Progress", fixture.frame.title)
                    assertEquals(90, fixture.published.last().percentage)
                    assertEquals(before + 1, fixture.published.size, "A burst uses one latest-state native update")
                }
            }
        }
    }

    @Test
    fun `focused split controls shared tab and window presentation`(
        @TempDir directory: Path,
    ) {
        Fixture(directory).use { fixture ->
            val first = fixture.open()
            onEdt { first.tab.customTitle = "First" }
            fixture.report(first.tab, 1, 25)
            val second = fixture.split(first)
            onEdt { second.tab.customTitle = "Second" }
            fixture.report(second.tab, 4, 80)
            onEdt {
                assertEquals("[Warning 80%] Second", fixture.frame.title)
                fixture.focus(first.terminal)
                assertEquals("[25%] First", fixture.bar.selectedDisplayTitle())
                assertEquals("[25%] First", fixture.frame.title)
                fixture.focus(second.terminal)
                assertEquals("[Warning 80%] Second", fixture.frame.title)
                fixture.manager.closePane(second)
                assertEquals("[25%] First", fixture.frame.title)
                assertEquals(25, fixture.published.last().percentage)
            }
        }
    }

    private class Fixture(
        directory: Path,
    ) : AutoCloseable {
        private val profile = TerminalProfile("progress", "Progress", listOf("unused"), workingDirectory = directory)
        private val connectors = CopyOnWriteArrayList<ControlledConnector>()
        private val nextStarted = AtomicReference<CompletableFuture<Unit>?>()
        private val focusManager = ProgressFocusManager()
        private val previousFocusManager =
            onEdt {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().also {
                    KeyboardFocusManager.setCurrentKeyboardFocusManager(focusManager)
                }
            }
        private val expectation = AtomicReference<Expectation?>()
        val bar = onEdt { TabBar({}, {}, {}, { _, _ -> }, { _, _ -> }, { _, _ -> }) }
        private val content = onEdt { JPanel(CardLayout()) }
        val frame =
            onEdt {
                JFrame().apply {
                    contentPane = content
                    pack()
                    setSize(800, 500)
                    validate()
                }
            }
        val published = ArrayList<TerminalProgress>()
        val manager: TabManager

        init {
            val configManager = KetraTermConfigManager(directory.resolve("config.toml"))
            configManager.save(
                KetraTermConfig(
                    cursorBlinkMillis = 0,
                    smartSuggestionsEnabled = false,
                    showForegroundProcessName = false,
                    titlePermission = TerminalTitlePermission.DENY,
                ),
            )
            manager =
                onEdt {
                    TabManager(
                        frame,
                        bar,
                        content,
                        KetraTermSettings(configManager),
                        { profile },
                        { true },
                        workspaceFactory = { listener ->
                            val recording =
                                object : TerminalWorkspaceListener by listener {
                                    override fun progressChanged(
                                        tab: TerminalWorkspaceTab,
                                        progress: TerminalProgress,
                                    ) {
                                        listener.progressChanged(tab, progress)
                                        expectation.get()?.let { expected ->
                                            if (tab === expected.tab &&
                                                progress.state == expected.state &&
                                                progress.percentage == expected.percentage
                                            ) {
                                                expected.delivered.complete(Unit)
                                            }
                                        }
                                    }
                                }
                            workspace(recording) { options ->
                                val connector = ControlledConnector { nextStarted.getAndSet(null)?.complete(Unit) }
                                connectors += connector
                                TerminalSession
                                    .create(
                                        TerminalBuffers.create(options.columns, options.rows),
                                        connector,
                                        hostPolicy = options.hostPolicy,
                                    ).also { connector.session = it }
                            }
                        },
                        focusOwner = { focusManager.owner },
                        windowProgressPublisher = { progress ->
                            assertTrue(SwingUtilities.isEventDispatchThread())
                            published += progress
                        },
                    )
                }
        }

        fun open(): TerminalPane {
            val complete = CompletableFuture<Boolean>()
            onEdt { assertTrue(manager.openTab(profile, onComplete = complete::complete)) }
            assertTrue(complete.get(10, TimeUnit.SECONDS))
            return onEdt { requireNotNull(manager.selectedPane) }
        }

        fun split(first: TerminalPane): TerminalPane {
            val started = CompletableFuture<Unit>()
            nextStarted.set(started)
            onEdt { manager.splitPane(first, isVertical = true) }
            started.get(10, TimeUnit.SECONDS)
            return onEdt {
                fun terminals(container: Container): List<SwingTerminal> =
                    container.components.flatMap {
                        when (it) {
                            is SwingTerminal -> listOf(it)
                            is Container -> terminals(it)
                            else -> emptyList()
                        }
                    }
                focus(terminals(content).single { it !== first.terminal })
                requireNotNull(manager.selectedPane)
            }
        }

        fun focus(component: Component) = focusManager.focus(component)

        fun report(
            tab: TerminalWorkspaceTab,
            state: Int,
            percentage: Int,
            drain: Boolean = true,
        ) {
            val expected =
                when (state) {
                    0 -> TerminalProgressState.HIDDEN
                    1 -> TerminalProgressState.NORMAL
                    2 -> TerminalProgressState.ERROR
                    3 -> TerminalProgressState.INDETERMINATE
                    4 -> TerminalProgressState.WARNING
                    else -> error("Unexpected test state")
                }
            emitAndAwait(tab, expected, percentage, "\u001B]9;4;$state;$percentage\u0007", drain)
        }

        fun emitAndAwait(
            tab: TerminalWorkspaceTab,
            state: TerminalProgressState,
            percentage: Int,
            bytes: String,
            drain: Boolean = true,
        ) {
            val expected = Expectation(tab, state, percentage)
            expectation.set(expected)
            // The owning session connector is captured before startup; tabs open in connector order.
            val connector = connectors.single { it.session === tab.session }
            connector.emit(bytes)
            expected.delivered.get(10, TimeUnit.SECONDS)
            expectation.compareAndSet(expected, null)
            if (drain) onEdt { }
        }

        override fun close() {
            val complete = CompletableFuture<Unit>()
            onEdt {
                try {
                    manager.closeAllTabsWithoutConfirmation { result -> result.fold(complete::complete, complete::completeExceptionally) }
                } finally {
                    frame.dispose()
                    KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager)
                }
            }
            complete.get(10, TimeUnit.SECONDS)
        }
    }

    private class ProgressFocusManager : DefaultKeyboardFocusManager() {
        var owner: Component? = null
            private set

        fun focus(component: Component) {
            val previous = owner
            owner = component
            firePropertyChange("permanentFocusOwner", previous, component)
        }
    }

    private class ControlledConnector(
        private val started: () -> Unit,
    ) : TerminalConnector {
        lateinit var session: TerminalSession
        private lateinit var listener: TerminalConnectorListener

        override fun start(listener: TerminalConnectorListener) {
            this.listener = listener
            started()
        }

        fun emit(text: String) {
            val bytes = text.encodeToByteArray()
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

    private class Expectation(
        val tab: TerminalWorkspaceTab,
        val state: TerminalProgressState,
        val percentage: Int,
        val delivered: CompletableFuture<Unit> = CompletableFuture(),
    )

    private companion object {
        private fun workspace(
            listener: TerminalWorkspaceListener,
            createSession: (TerminalWorkspaceOpenOptions) -> TerminalSession,
        ): TerminalWorkspace {
            val factoryType = Class.forName("io.github.ketraterm.workspace.TerminalWorkspaceSessionFactory")
            val factory =
                Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, method, args ->
                    check(method.name == "create")
                    createSession(requireNotNull(args)[1] as TerminalWorkspaceOpenOptions)
                }
            return TerminalWorkspace::class.java
                .getDeclaredConstructor(TerminalWorkspaceListener::class.java, factoryType, CoroutineDispatcher::class.java)
                .newInstance(listener, factory, Dispatchers.Default) as TerminalWorkspace
        }

        fun <T> onEdt(action: () -> T): T {
            val task = FutureTask(action)
            SwingUtilities.invokeLater(task)
            return task.get(10, TimeUnit.SECONDS)
        }
    }
}
