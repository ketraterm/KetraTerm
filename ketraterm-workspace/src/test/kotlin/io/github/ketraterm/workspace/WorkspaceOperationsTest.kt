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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceOperationsTest {
    @Test
    fun `full queue rejects excess work but reserves shutdown after all accepted operations`() =
        runTest {
            val operations = WorkspaceOperations(StandardTestDispatcher(testScheduler))
            val observed = mutableListOf<Int>()
            repeat(WorkspaceOperations.CAPACITY) { index -> assertTrue(operations.submit { observed += index }) }
            assertFalse(operations.submit { fail("Rejected operation ran") })
            assertTrue(operations.submitCleanup(listOf("owned-tab")) { observed += -2 })
            assertFalse(operations.submitCleanup(listOf("owned-tab")) { fail("Duplicate cleanup ran") })
            assertTrue(operations.finish { observed += -1 })
            assertFalse(operations.submit { fail("Operation accepted after shutdown") })
            assertFalse(operations.finish { fail("Duplicate shutdown ran") })
            assertTrue(observed.isEmpty())
            runCurrent()
            assertEquals((0 until WorkspaceOperations.CAPACITY).toList() + listOf(-2, -1), observed)
        }

    @Test
    fun `throwing completion cannot strand later work or cleanup`() =
        runTest {
            val operations = WorkspaceOperations(StandardTestDispatcher(testScheduler))
            val observed = mutableListOf<String>()
            assertTrue(
                operations.submit {
                    observed += "first"
                    error("consumer failed")
                },
            )
            assertTrue(operations.submit { observed += "second" })
            assertTrue(operations.finish { observed += "cleanup" })
            runCurrent()
            assertEquals(listOf("first", "second", "cleanup"), observed)
        }

    @Test
    fun `EDT submission and disposal remain responsive while ordered cleanup waits`() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val operations = WorkspaceOperations(dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
            try {
                assertTrue(
                    operations.submit {
                        check(!SwingUtilities.isEventDispatchThread())
                        observed += "entered"
                        entered.countDown()
                        release.await()
                        observed += "released"
                    },
                )
                assertTrue(entered.await(10, TimeUnit.SECONDS), "Worker never entered its blocked operation")
                val edt =
                    FutureTask {
                        assertTrue(operations.submit { observed += "next" })
                        assertTrue(
                            operations.finish {
                                observed += "cleanup"
                                completed.countDown()
                            },
                        )
                        observed += "EDT marker"
                    }
                SwingUtilities.invokeLater(edt)
                edt.get(10, TimeUnit.SECONDS)
                assertEquals(listOf("entered", "EDT marker"), observed)
            } finally {
                release.countDown()
                operations.finish { completed.countDown() }
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS), "Accepted cleanup was not completed")
            assertEquals(listOf("entered", "EDT marker", "released", "next", "cleanup"), observed)
        }
    }
}
