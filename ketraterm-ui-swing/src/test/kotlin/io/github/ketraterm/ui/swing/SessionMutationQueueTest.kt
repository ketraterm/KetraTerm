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
package io.github.ketraterm.ui.swing

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class SessionMutationQueueTest {
    @Test
    fun `settings and policies remain ordered while only adjacent geometry coalesces`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, dispatcher, dispatcher)
            val applied = mutableListOf<String>()
            val completed = mutableListOf<String>()

            fun submit(
                name: String,
                geometry: Boolean = false,
            ) = queue.submit(session, { true }, geometry, apply = {
                applied.add(name)
                null
            }, complete = {
                completed.add("$name:${if (it == null) "applied" else "cancelled"}")
            })
            try {
                submit("settings")
                submit("old-size", true)
                submit("size", true)
                submit("deny")
                submit("next-size", true)
                submit("permit")
                runCurrent()
                assertEquals(listOf("settings", "size", "deny", "next-size", "permit"), applied)
                assertEquals(
                    listOf("old-size:cancelled", "settings:applied", "size:applied", "deny:applied", "next-size:applied", "permit:applied"),
                    completed,
                )
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `obsolete requests are cancelled before mutation and before installation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, dispatcher, dispatcher)
            val current = AtomicBoolean(true)
            var mutations = 0
            var installations = 0
            val completions = mutableListOf<Throwable?>()
            try {
                queue.submit(session, current::get, apply = {
                    mutations++;
                    { installations++ }
                }, complete = completions::add)
                current.set(false)
                runCurrent()
                assertEquals(0, mutations)
                assertEquals(0, installations)
                assertInstanceOf(CancellationException::class.java, completions.single())

                current.set(true)
                completions.clear()
                queue.submit(session, current::get, apply = {
                    mutations++
                    current.set(false);
                    { installations++ }
                }, complete = completions::add)
                runCurrent()
                assertEquals(1, mutations)
                assertEquals(0, installations)
                assertInstanceOf(CancellationException::class.java, completions.single())
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `capacity rejection leaves all accepted policies intact`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, dispatcher, dispatcher)
            val applied = mutableListOf<Int>()
            var completions = 0
            try {
                repeat(64) { index ->
                    queue.submit(session, { true }, apply = {
                        applied.add(index)
                        null
                    }, complete = {
                        assertNull(it)
                        completions++
                    })
                }
                assertThrows(IllegalStateException::class.java) {
                    queue.submit(
                        session,
                        { true },
                        apply = { fail("Rejected request must not run") },
                        complete = { fail("Rejected request was not accepted") },
                    )
                }
                runCurrent()
                assertEquals((0 until 64).toList(), applied)
                assertEquals(64, completions)
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `mutation and installation failures complete once and do not strand following work`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, dispatcher, dispatcher)
            val mutationFailure = IllegalArgumentException("mutation")
            val installFailure = IllegalStateException("install")
            val completions = mutableListOf<Throwable?>()
            try {
                queue.submit(session, { true }, apply = { throw mutationFailure }, complete = completions::add)
                queue.submit(session, { true }, apply = { { throw installFailure } }, complete = completions::add)
                queue.submit(session, { true }, apply = { null }, complete = completions::add)
                runCurrent()
                assertEquals(listOf(mutationFailure, installFailure, null), completions)
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `disposal completes every pending request despite a completion failure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, dispatcher, dispatcher)
            val completions = mutableListOf<Throwable?>()
            val failure = IllegalStateException("completion")
            try {
                repeat(3) { index ->
                    queue.submit(session, { true }, apply = { fail("Disposed mutation") }, complete = {
                        completions.add(it)
                        if (index == 0) throw failure
                    })
                }
                assertSame(failure, assertThrows(IllegalStateException::class.java) { queue.close() })
                runCurrent()
                assertEquals(3, completions.size)
                completions.forEach { assertInstanceOf(CancellationException::class.java, it) }
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `worker awaits EDT installation and outstanding requests remain bounded`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val deliveries = ArrayDeque<Runnable>()
            val ui =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        deliveries.addLast(block)
                    }
                }
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, worker, ui)
            var applied = 0
            try {
                repeat(64) {
                    queue.submit(session, { true }, apply = {
                        applied++
                        null
                    }, complete = { assertNull(it) })
                }
                runCurrent()
                assertEquals(1, applied)
                assertEquals(1, deliveries.size)
                assertThrows(IllegalStateException::class.java) {
                    queue.submit(session, { true }, apply = { null }, complete = { fail("Rejected") })
                }
                deliveries.removeFirst().run()
                queue.submit(session, { true }, apply = {
                    applied++
                    null
                }, complete = { assertNull(it) })
                runCurrent()
                assertEquals(2, applied)
                while (deliveries.isNotEmpty()) {
                    deliveries.removeFirst().run()
                    runCurrent()
                }
                assertEquals(65, applied)
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `completion failure does not strand following accepted work`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, dispatcher, dispatcher)
            val applied = mutableListOf<String>()
            var completions = 0
            try {
                queue.submit(session, { true }, apply = { null }, complete = {
                    completions++
                    throw IllegalStateException("Host completion failed")
                })
                for (name in listOf("policy", "geometry")) {
                    queue.submit(session, { true }, geometry = name == "geometry", apply = {
                        applied.add(name)
                        null
                    }, complete = {
                        assertNull(it)
                        completions++
                    })
                }
                runCurrent()
                assertEquals(listOf("policy", "geometry"), applied)
                assertEquals(3, completions)
            } finally {
                queue.close()
                session.close()
            }
        }

    @Test
    fun `admitted geometry installs before a later geometry request`() =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val deliveries = ArrayDeque<Runnable>()
            val ui =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        deliveries.addLast(block)
                    }
                }
            val session = session()
            val queue = SessionMutationQueue(backgroundScope, worker, ui)
            val events = mutableListOf<String>()

            fun submit(name: String) =
                queue.submit(session, { true }, geometry = true, apply = {
                    events.add("$name:apply");
                    { events.add("$name:install") }
                }, complete = {
                    assertNull(it)
                    events.add("$name:complete")
                })
            try {
                submit("first")
                runCurrent()
                submit("second")
                assertEquals(listOf("first:apply"), events)
                while (deliveries.isNotEmpty()) {
                    deliveries.removeFirst().run()
                    runCurrent()
                }
                assertEquals(
                    listOf("first:apply", "first:install", "first:complete", "second:apply", "second:install", "second:complete"),
                    events,
                )
            } finally {
                queue.close()
                session.close()
            }
        }

    private fun session(): TerminalSession =
        TerminalSession.create(
            TerminalBuffers.create(10, 2),
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) = Unit

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
            },
        )
}
