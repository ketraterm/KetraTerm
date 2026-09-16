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
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.settings.KetraTermProjectSettings
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.workspace.TerminalProfile
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Verifies restart metadata through the project service without showing terminal panes or creating PTYs. */
class KetraTermProjectTerminalPersistenceTest : BasePlatformTestCase() {
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
            KetraTermProjectTerminalService(project) { _, profile, _ ->
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

    private fun service(onStart: () -> Unit = {}): KetraTermProjectTerminalService =
        KetraTermProjectTerminalService(project) { _, _, _ ->
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
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                else -> error("Unexpected tool-window call: ${method.name}")
            }
        } as ToolWindow
    }
}
