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
package io.github.ketraterm.intellij.services

import com.intellij.ide.impl.TrustedPaths
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerListener
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.settings.KetraTermProjectSettings
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalWorkspace
import io.github.ketraterm.workspace.TerminalWorkspaceListener
import io.github.ketraterm.workspace.TerminalWorkspaceOpenOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.lang.reflect.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Verifies restart metadata and startup lifecycle through real product panes without creating PTYs. */
class KetraTermProjectTerminalPersistenceTest : BasePlatformTestCase() {
    fun testDisposalFinishesWhenPersistenceListenerRemovalThrows() {
        storage.loadState(TerminalTabsState(listOf(TerminalTabState(customTitle = "Pending")), 0))
        val service = service()
        val delegate = contentManager()
        val failure = IllegalStateException("listener removal failed")
        var removals = 0
        val manager =
            object : ContentManager by delegate {
                override fun removeContentManagerListener(listener: ContentManagerListener) {
                    removals++
                    delegate.removeContentManagerListener(listener)
                    throw failure
                }
            }
        service.ensureInitialTab(toolWindow(manager))
        assertTrue(service.hasOpenTabs())
        try {
            service.dispose()
            fail("Expected the cleanup failure")
        } catch (reported: IllegalStateException) {
            assertSame(failure, reported)
        }
        assertFalse("Disposal must clear every pending tab despite listener failure", service.hasOpenTabs())
        service.dispose()
        assertEquals(1, removals)
    }

    private lateinit var lifetime: Disposable
    private lateinit var storage: KetraTermTerminalTabsStorage
    private lateinit var originalTabs: TerminalTabsState
    private lateinit var originalSettings: KetraTermIntellijSettings.State

    override fun setUp() {
        super.setUp()
        lifetime = Disposer.newDisposable(testRootDisposable, "terminal project persistence fixture")
        storage = project.service()
        originalTabs = storage.state
        storage.loadState(TerminalTabsState())
        val settings = KetraTermIntellijSettings.getInstance()
        originalSettings = settings.state
        settings.loadState(originalSettings.copy(addProjectJdkToPath = false))
    }

    override fun tearDown() {
        try {
            Disposer.dispose(lifetime)
            storage.loadState(originalTabs)
            KetraTermIntellijSettings.getInstance().loadState(originalSettings)
        } finally {
            super.tearDown()
        }
    }

    fun testPlatformConstructsTheProjectServiceThroughItsProductionConstructor() {
        val service = KetraTermProjectTerminalService.getInstance(project)

        assertSame(service, KetraTermProjectTerminalService.getInstance(project))
        assertFalse(service.hasOpenTabs())
    }

    fun testRestoresTitlesOrderAndSelectionWithoutStartingHiddenTabs() {
        val saved =
            TerminalTabsState(
                listOf(
                    TerminalTabState(customTitle = "Server", workingDirectory = "/server"),
                    TerminalTabState(customTitle = "Tests", workingDirectory = "/tests"),
                    TerminalTabState(customTitle = "Build", workingDirectory = "/build"),
                ),
                selectedTabIndex = 1,
            )
        storage.loadState(saved)
        val starts = AtomicInteger()
        val service = service { starts.incrementAndGet() }
        val manager = contentManager()
        val window = toolWindow(manager)

        service.ensureInitialTab(window)
        repeat(3) { service.ensureInitialTab(window) }

        assertEquals(listOf("Server", "Tests", "Build"), manager.contents.map { it.displayName })
        assertSame(manager.getContent(1), manager.selectedContent)
        assertEquals(saved, storage.state)
        assertTrue(service.hasOpenTabs())
        assertEquals("Hidden restored tabs must not start processes", 0, starts.get())
    }

    fun testManualOpenBeforeFactoryInitializationDoesNotCreateAnExtraDefaultTab() {
        val starts = AtomicInteger()
        val service = service { starts.incrementAndGet() }
        val manager = contentManager()
        lateinit var window: ToolWindow
        window = toolWindow(manager) { service.ensureInitialTab(window) }

        val opened = service.openProfileTab(window, TerminalProfile("test-profile", "Explicit Shell", listOf("unused-shell")))
        service.ensureInitialTab(window)
        PlatformTestUtil.waitWithEventsDispatching(
            "Explicit terminal startup result was not published",
            { opened.displayName == "Failed: Explicit Shell" },
            10,
        )

        assertEquals(1, manager.contentCount)
        assertSame(opened, manager.selectedContent)
        assertEquals(1, starts.get())
        assertEquals(1, storage.state.tabs.size)
        assertNull(
            "Startup failure labels must not become custom titles",
            storage.state.tabs
                .single()
                .customTitle,
        )
    }

    fun testClosingRestoredPendingTabUpdatesSavedTabsAndDoesNotRestoreItAgain() {
        val first = TerminalTabState(customTitle = "Closed before startup")
        val second = TerminalTabState(customTitle = "Retained")
        storage.loadState(TerminalTabsState(listOf(first, second), 0))
        val starts = AtomicInteger()
        val service = service { starts.incrementAndGet() }
        val manager = contentManager()
        val window = toolWindow(manager)
        service.ensureInitialTab(window)

        assertTrue(manager.removeContent(requireNotNull(manager.getContent(0)), true))
        service.ensureInitialTab(window)

        assertEquals(listOf("Retained"), manager.contents.map { it.displayName })
        assertEquals(TerminalTabsState(listOf(second), 0), storage.state)
        assertEquals(0, starts.get())
    }

    fun testProjectBeforeSaveCapturesTabsAndClosingFreezesThemBeforeContentRemoval() {
        val saved = TerminalTabsState(listOf(TerminalTabState(customTitle = "Remember me")), 0)
        storage.loadState(saved)
        val service = service()
        val manager = contentManager()
        val window = toolWindow(manager)
        service.ensureInitialTab(window)
        storage.replace(TerminalTabsState())
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(ProjectCloseListener.TOPIC)

        publisher.projectClosingBeforeSave(project)

        assertEquals(saved, storage.state)

        publisher.projectClosing(project)
        manager.removeAllContents(true)
        service.ensureInitialTab(window)

        assertEquals(0, manager.contentCount)
        assertEquals(saved, storage.state)
    }

    fun testCancelledProjectCloseKeepsCapturingTabChanges() {
        val first = TerminalTabState(customTitle = "First")
        val second = TerminalTabState(customTitle = "Second")
        storage.loadState(TerminalTabsState(listOf(first, second), 0))
        val service = service()
        val manager = contentManager()
        service.ensureInitialTab(toolWindow(manager))
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(ProjectCloseListener.TOPIC)

        publisher.projectClosingBeforeSave(project)
        manager.setSelectedContent(requireNotNull(manager.getContent(1)), false)

        assertEquals(TerminalTabsState(listOf(first, second), 1), storage.state)

        assertTrue(manager.removeContent(requireNotNull(manager.getContent(0)), true))

        assertEquals(TerminalTabsState(listOf(second), 0), storage.state)
        assertTrue(service.hasOpenTabs())
    }

    fun testUntrustedProjectPreservesSavedTabsAndRestoresWhenTrustIsGranted() {
        val trustedPaths = TrustedPaths.getInstance()
        val originalTrust = trustedPaths.state.copy(trustedPaths = trustedPaths.state.trustedPaths.toMap())
        val saved = TerminalTabsState(listOf(TerminalTabState(customTitle = "Waiting for trust")), 0)
        storage.loadState(saved)
        val starts = AtomicInteger()
        val service = service { starts.incrementAndGet() }
        val manager = contentManager()
        val window = toolWindow(manager)
        try {
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse(TrustedProjects.isProjectTrusted(project))

            service.ensureInitialTab(window)

            assertEquals(0, manager.contentCount)
            assertEquals(saved, storage.state)
            assertFalse(service.hasOpenTabs())
            assertEquals(0, starts.get())

            TrustedProjects.setProjectTrusted(project, true)
            PlatformTestUtil.waitWithEventsDispatching(
                "Granting project trust did not restore the pending terminal workspace",
                { manager.contentCount == 1 },
                10,
            )

            assertEquals("Waiting for trust", manager.selectedContent?.displayName)
            assertEquals(saved, storage.state)
            assertEquals(0, starts.get())
        } finally {
            Disposer.dispose(service)
            trustedPaths.loadState(originalTrust)
        }
    }

    fun testServiceCreationAndDisposalWithoutToolWindowInitializationPreserveSavedTabs() {
        val saved = TerminalTabsState(listOf(TerminalTabState(customTitle = "Unopened")), 0)
        storage.loadState(saved)
        val modifications = storage.stateModificationCount
        val service = service()
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(ProjectCloseListener.TOPIC)

        publisher.projectClosingBeforeSave(project)
        publisher.projectClosing(project)
        Disposer.dispose(service)

        assertEquals(saved, storage.state)
        assertEquals(modifications, storage.stateModificationCount)
    }

    fun testNewTerminalsUseCurrentProjectCommandAndPreserveExplicitProfileCommand() {
        val projectSettings = project.service<KetraTermProjectSettings>()
        val original = projectSettings.state
        val profiles = CopyOnWriteArrayList<TerminalProfile>()
        val service =
            KetraTermProjectTerminalService(project) { profile ->
                profiles += profile
                throw IllegalStateException("PTY startup is disabled in this fixture")
            }.also { Disposer.register(lifetime, it) }
        val window = toolWindow(contentManager())
        try {
            projectSettings.replaceState(KetraTermProjectSettings.State("echo first"))
            val first = service.openDefaultTab(window)
            PlatformTestUtil.waitWithEventsDispatching("Default startup was not published", { first.displayName.startsWith("Failed:") }, 10)
            assertEquals("echo first", profiles.single().startupCommand?.text)

            projectSettings.replaceState(KetraTermProjectSettings.State("echo second"))
            val profile = TerminalProfile("bash", "Bash", listOf("bash", "-i"))
            val second = service.openProfileTab(window, profile)
            PlatformTestUtil.waitWithEventsDispatching(
                "Selected startup was not published",
                { second.displayName.startsWith("Failed:") },
                10,
            )
            assertEquals("echo second", profiles[1].startupCommand?.text)
            assertEquals(profile.command, profiles[1].command)

            val explicit = profile.copy(startupCommand = TerminalStartupCommand("echo explicit"))
            val third = service.openProfileTab(window, explicit)
            PlatformTestUtil.waitWithEventsDispatching(
                "Explicit startup was not published",
                { third.displayName.startsWith("Failed:") },
                10,
            )
            assertEquals(explicit.startupCommand, profiles[2].startupCommand)

            projectSettings.replaceState(KetraTermProjectSettings.State())
            val fourth = service.openProfileTab(window, profile)
            PlatformTestUtil.waitWithEventsDispatching(
                "Disabled startup was not published",
                { fourth.displayName.startsWith("Failed:") },
                10,
            )
            assertNull(profiles[3].startupCommand)
        } finally {
            projectSettings.loadState(original)
        }
    }

    fun testPermissionRevocationDuringBindingSurvivesStartupCompletion() {
        val settings = KetraTermIntellijSettings.getInstance()
        settings.loadState(settings.state.copy(titlePermission = "allow", smartSuggestionsEnabled = false, cursorBlinkMillis = 0))
        val releaseAdmission = CountDownLatch(1)
        val gateTask = CompletableFuture<FutureTask<Unit>>()
        val paneInstalled = CompletableFuture<Unit>()
        val started = CompletableFuture<Unit>()
        val titles = CopyOnWriteArrayList<String>()
        val service =
            KetraTermProjectTerminalService(
                project,
                workspaceFactory = { listener ->
                    workspace(listener) { options ->
                        val session =
                            TerminalSession.create(
                                TerminalBuffers.create(options.columns, options.rows),
                                connector(onStart = { output ->
                                    check(paneInstalled.isDone) { "Output started before pane installation" }
                                    val bytes = "\u001B]2;revoked-title\u0007".encodeToByteArray()
                                    output.onBytes(bytes, 0, bytes.size)
                                    started.complete(Unit)
                                }),
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
                            .name("intellij-startup-admission")
                            .start(gate)
                        check(entered.await(10, TimeUnit.SECONDS)) { "Admission gate was not entered" }
                        session
                    }
                },
                prepareStartup = {},
            ).also { Disposer.register(lifetime, it) }
        try {
            val content = service.openProfileTab(toolWindow(contentManager()), TerminalProfile("startup", "Startup", listOf("unused")))
            observePaneInstallation(service, content, paneInstalled)
            PlatformTestUtil.waitWithEventsDispatching("Pane was not installed", { paneInstalled.isDone }, 10)
            settings.replaceState(settings.state.copy(titlePermission = "deny"))
            // replaceState invokes the service listener synchronously; its queued reload precedes this marker.
            val settingsReloaded = CompletableFuture<Unit>()
            ApplicationManager.getApplication().invokeLater { settingsReloaded.complete(Unit) }
            PlatformTestUtil.waitWithEventsDispatching("Queued settings reload did not run", { settingsReloaded.isDone }, 10)
            assertFalse("Startup must await initial binding", started.isDone)
            releaseAdmission.countDown()
            gateTask.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            PlatformTestUtil.waitWithEventsDispatching("Connector was not started", { started.isDone }, 10)
            assertEquals("Binding completion must not restore the startup permission snapshot", emptyList<String>(), titles)
        } finally {
            releaseAdmission.countDown()
            if (gateTask.isDone) gateTask.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            Disposer.dispose(service)
        }
    }

    fun testClosingStartupFailureAfterBindingRemovesThePendingTab() {
        val settings = KetraTermIntellijSettings.getInstance()
        settings.loadState(settings.state.copy(smartSuggestionsEnabled = false, cursorBlinkMillis = 0))
        val paneInstalled = CompletableFuture<Unit>()
        val startedAfterBinding = AtomicBoolean()
        val connectorCloses = AtomicInteger()
        val createdSession = CompletableFuture<TerminalSession>()
        val service =
            KetraTermProjectTerminalService(
                project,
                workspaceFactory = { listener ->
                    workspace(listener) { options ->
                        TerminalSession
                            .create(
                                TerminalBuffers.create(options.columns, options.rows),
                                connector(
                                    onStart = {
                                        startedAfterBinding.set(paneInstalled.isDone)
                                        throw IllegalStateException("Connector startup failed after pane binding")
                                    },
                                    onClose = { connectorCloses.incrementAndGet() },
                                ),
                                hostPolicy = options.hostPolicy,
                            ).also(createdSession::complete)
                    }
                },
                prepareStartup = {},
            ).also { Disposer.register(lifetime, it) }
        val manager = contentManager()
        val content = service.openProfileTab(toolWindow(manager), TerminalProfile("startup", "Startup", listOf("unused")))
        observePaneInstallation(service, content, paneInstalled)
        PlatformTestUtil.waitWithEventsDispatching("Startup failure was not published", { content.displayName == "Failed: Startup" }, 10)
        assertTrue("The connector must fail after the actual pane was installed", startedAfterBinding.get())
        assertTrue(createdSession.get(10, TimeUnit.SECONDS).isClosed)
        assertEquals(1, connectorCloses.get())
        assertTrue(service.hasOpenTabs())

        manager.removeContent(content, true)

        assertEquals(0, manager.contentCount)
        assertFalse("Closing failed startup content must release its pending tab", service.hasOpenTabs())
    }

    private fun observePaneInstallation(
        service: KetraTermProjectTerminalService,
        content: Content,
        installed: CompletableFuture<Unit>,
    ) {
        content.component.addContainerListener(
            object : ContainerAdapter() {
                override fun componentAdded(event: ContainerEvent) {
                    if (service.paneForComponent(event.child) != null) installed.complete(Unit)
                }
            },
        )
        if (content.component.components.any { service.paneForComponent(it) != null }) installed.complete(Unit)
    }

    private fun connector(
        onStart: (TerminalConnectorListener) -> Unit,
        onClose: () -> Unit = {},
    ): TerminalConnector =
        object : TerminalConnector {
            override fun start(listener: TerminalConnectorListener) = onStart(listener)

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

    private fun service(onStart: () -> Unit = {}): KetraTermProjectTerminalService =
        KetraTermProjectTerminalService(project) { _ ->
            onStart()
            throw IllegalStateException("PTY startup is disabled in this fixture")
        }.also { Disposer.register(lifetime, it) }

    private fun contentManager(): ContentManager =
        ContentFactory.getInstance().createContentManager(true, project).also { Disposer.register(lifetime, it) }

    private fun toolWindow(
        manager: ContentManager,
        onFirstContentAccess: () -> Unit = {},
    ): ToolWindow {
        var accessed = false
        return Proxy.newProxyInstance(ToolWindow::class.java.classLoader, arrayOf(ToolWindow::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "getContentManager" -> {
                    if (!accessed) {
                        accessed = true
                        onFirstContentAccess()
                    }
                    manager
                }
                "toString" -> "KetraTerm persistence test tool window"
                "isActive" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                else -> error("Unexpected tool-window call: ${method.name}")
            }
        } as ToolWindow
    }
}
