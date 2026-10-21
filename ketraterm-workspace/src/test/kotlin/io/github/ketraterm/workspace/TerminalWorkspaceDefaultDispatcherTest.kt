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
package io.github.ketraterm.workspace

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Runs in the defaultDispatcherTest JVM, with two CPU workers configured before initialization. */
class TerminalWorkspaceDefaultDispatcherTest {
    @Test
    fun `concurrent default startups leave CPU workers available for host binding`() {
        assertEquals("2", System.getProperty("kotlinx.coroutines.scheduler.core.pool.size"))
        val published = CountDownLatch(2)
        val bindings = List(2) { CompletableFuture<Unit>() }
        val opened = List(2) { CompletableFuture<TerminalWorkspaceTab>() }
        val sessions = List(2) { TerminalSession.create(TerminalBuffers.create(80, 24), MockConnector()) }
        val workspaces =
            List(2) { index ->
                TerminalWorkspace(
                    listener =
                        object : TerminalWorkspaceListener {
                            override fun tabOpened(tab: TerminalWorkspaceTab) {
                                published.countDown()
                                assertTrue(published.await(10, TimeUnit.SECONDS), "Both startups must reach publication before binding")
                                // Models an asynchronous host binding that must apply session settings
                                // before the workspace may start connector output.
                                Dispatchers.Default.dispatch(EmptyCoroutineContext) {
                                    try {
                                        tab.session.withMutationAdmission({ true }) { tab.session.setWindowMinimized(false) }
                                        bindings[index].complete(Unit)
                                    } catch (failure: Throwable) {
                                        bindings[index].completeExceptionally(failure)
                                    }
                                }
                                bindings[index].get(10, TimeUnit.SECONDS)
                            }
                        },
                    sessionFactory = { _, _, _ -> sessions[index] },
                )
            }
        try {
            workspaces.forEachIndexed { index, workspace ->
                assertTrue(
                    workspace.openTabAsync({ TerminalProfile("test-$index", "Test", listOf("unused")) }, TerminalWorkspaceOpenOptions()) {
                        it.fold(opened[index]::complete, opened[index]::completeExceptionally)
                    },
                )
            }
            opened.forEachIndexed { index, result ->
                assertSame(sessions[index], result.get(10, TimeUnit.SECONDS).session)
                assertEquals(Unit, bindings[index].get())
            }
        } finally {
            bindings.forEach { it.complete(Unit) }
            workspaces.forEach { workspace ->
                val closed = CompletableFuture<Unit>()
                assertTrue(workspace.closeAsync { it.fold(closed::complete, closed::completeExceptionally) })
                closed.get(10, TimeUnit.SECONDS)
            }
            sessions.forEach(TerminalSession::close)
        }
    }
}
