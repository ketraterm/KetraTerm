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
package io.github.ketraterm.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.intellij.services.KetraTermCompletionService
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.CancellationException
import java.awt.event.AdjustmentListener
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JScrollBar

class KetraTermTerminalPaneLifecycleTest : BasePlatformTestCase() {
    fun testCloseRemovesOnlyThePaneScrollbarListener() {
        val settings = KetraTermIntellijSettings()
        settings.loadState(settings.state.copy(smartSuggestionsEnabled = false, cursorBlinkMillis = 0))
        ApplicationManager.getApplication().replaceService(KetraTermIntellijSettings::class.java, settings, testRootDisposable)
        TerminalSession.create(TerminalBuffers.create(30, 4, 0), MockConnector()).use { session ->
            val pane = KetraTermTerminalPane.create(project, createTab(session), TerminalClipboardHandler.SYSTEM)
            val scrollbar =
                pane.component.components
                    .filterIsInstance<JScrollBar>()
                    .single()
            val hostListener = AdjustmentListener {}
            scrollbar.addAdjustmentListener(hostListener)
            val attachedListeners = scrollbar.adjustmentListeners.toList()
            try {
                pane.close()
                val remainingListeners = scrollbar.adjustmentListeners.toList()
                assertEquals(attachedListeners.size - 1, remainingListeners.size)
                assertTrue("Closing a pane must preserve host-owned listeners", hostListener in remainingListeners)
                assertFalse("Pane closure does not own the workspace session", session.isClosed)
                pane.close()
                assertEquals("Repeated close must not remove other listeners", remainingListeners, scrollbar.adjustmentListeners.toList())
            } finally {
                pane.close()
            }
        }
    }

    fun testCreationRollsBackAfterCompletionResourceFailure() = verifyRollback(IllegalStateException("resource lookup failed"))

    fun testCreationRollsBackAfterCompletionResourceCancellation() = verifyRollback(CancellationException("resource lookup cancelled"))

    fun testCreationRetainsOriginalFailureWhenRollbackAlsoFails() =
        verifyRollback(IllegalStateException("resource lookup failed"), IllegalArgumentException("resource release failed"))

    private fun verifyRollback(
        failure: RuntimeException,
        cleanupFailure: RuntimeException? = null,
    ) {
        val settings = KetraTermIntellijSettings()
        settings.loadState(settings.state.copy(smartSuggestionsEnabled = true, cursorBlinkMillis = 0))
        val application = ApplicationManager.getApplication()
        application.replaceService(KetraTermIntellijSettings::class.java, settings, testRootDisposable)
        val service = KetraTermCompletionService()
        application.replaceService(KetraTermCompletionService::class.java, service, testRootDisposable)
        val listeners = CopyOnWriteArrayList<() -> Unit>()
        var acquiredPane: KetraTermTerminalPane? = null
        val hostScrollbarListener = AdjustmentListener {}
        var attachedScrollbarListeners = emptyList<AdjustmentListener>()
        // Observe the actual private registration to inspect resources acquired before create returns.
        // No production factory hook is needed for this failing service boundary.
        val resources =
            object : IdentityHashMap<TerminalWorkspaceTab, SwingShellSuggestionProvider>() {
                override fun get(key: TerminalWorkspaceTab): SwingShellSuggestionProvider? {
                    val listener = listeners.single()
                    acquiredPane =
                        listener.javaClass.declaredFields
                            .single { it.type == KetraTermTerminalPane::class.java }
                            .apply { isAccessible = true }
                            .get(listener) as KetraTermTerminalPane
                    val scrollbar =
                        requireNotNull(acquiredPane)
                            .component.components
                            .filterIsInstance<JScrollBar>()
                            .single()
                    scrollbar.addAdjustmentListener(hostScrollbarListener)
                    attachedScrollbarListeners = scrollbar.adjustmentListeners.toList()
                    throw failure
                }

                override fun remove(key: TerminalWorkspaceTab): SwingShellSuggestionProvider? {
                    cleanupFailure?.let { throw it }
                    return super.remove(key)
                }
            }
        for ((name, value) in listOf("providerListeners" to listeners, "providersByTab" to resources)) {
            KetraTermCompletionService::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(service, value)
        }
        val connector = MockConnector()
        TerminalSession.create(TerminalBuffers.create(30, 4, 0), connector).use { session ->
            val tab = createTab(session)
            try {
                assertSame(
                    failure,
                    org.junit.Assert.assertThrows(RuntimeException::class.java) {
                        KetraTermTerminalPane.create(project, tab, TerminalClipboardHandler.SYSTEM)
                    },
                )
                val pane = requireNotNull(acquiredPane)
                assertEquals(listOfNotNull(cleanupFailure), failure.suppressed.toList())
                assertTrue("Failed creation must remove its service listener", listeners.isEmpty())
                assertTrue(resources.isEmpty())
                assertFalse("Pane rollback does not own the workspace session", session.isClosed)
                val scrollbar =
                    pane.component.components
                        .filterIsInstance<JScrollBar>()
                        .single()
                val remainingScrollbarListeners = scrollbar.adjustmentListeners.toList()
                assertEquals(attachedScrollbarListeners.size - 1, remainingScrollbarListeners.size)
                assertTrue("Rollback must preserve host-owned scrollbar listeners", hostScrollbarListener in remainingScrollbarListeners)
                assertTrue(
                    "Shortcut hooks must be removed",
                    pane.terminal.mouseListeners.none {
                        it.javaClass.name.contains("KetraTermTerminalShortcutController")
                    },
                )
                assertNull(
                    "Rolled-back terminal must reject interaction creation",
                    pane.terminal.beginShellSuggestionInteraction(SwingShellSuggestionRequest("", 0)),
                )
                assertFalse("Rolled-back terminal must reject presentation", pane.terminal.currentShellSuggestionState().visible)
                pane.close()
                assertEquals(remainingScrollbarListeners, scrollbar.adjustmentListeners.toList())
            } finally {
                try {
                    acquiredPane?.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== cleanupFailure) throw cleanup
                } finally {
                    service.dispose()
                }
            }
        }
    }

    private fun createTab(session: TerminalSession): TerminalWorkspaceTab =
        TerminalWorkspaceTab::class.java.constructors.single { it.parameterCount == 8 }.newInstance(
            "test",
            TerminalProfile("test", "Test", listOf("unused")),
            "Test",
            session,
            { _: TerminalWorkspaceTab, _: String? -> },
            { _: TerminalWorkspaceTab, _: String -> },
            { _: TerminalWorkspaceTab, _: String -> },
            false,
        ) as TerminalWorkspaceTab
}
