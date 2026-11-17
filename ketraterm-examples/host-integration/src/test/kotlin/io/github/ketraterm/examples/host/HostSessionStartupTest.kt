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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class HostSessionStartupTest {
    @ParameterizedTest
    @EnumSource(StartupStage::class)
    fun `window closure releases blocked startup`(stage: StartupStage) =
        runBlocking {
            val connector = StartupConnector(stage)
            val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
            val closed = CompletableDeferred<Unit>()
            val startup = async { startBoundSession(session, CompletableDeferred(Unit), closed) }
            try {
                withTimeout(5.seconds) { connector.entered.await() }
                withContext(Dispatchers.Swing) { closed.complete(Unit) }
                withTimeout(5.seconds) { connector.closed.await() }
                assertFalse(withTimeout(5.seconds) { startup.await() })
                val state = withTimeout(5.seconds) { session.state.first { it is TerminalSessionState.Closed } }
                assertTrue(assertIs<TerminalSessionState.Closed>(state).event.locallyRequested)
                assertEquals(1, connector.closeCount.get())
            } finally {
                connector.release.countDown()
                withContext(NonCancellable + Dispatchers.IO) {
                    startup.cancelAndJoin()
                    session.close()
                }
            }
        }

    @ParameterizedTest
    @EnumSource(StartupStage::class)
    fun `host cancellation releases blocked startup`(stage: StartupStage) =
        runBlocking {
            val connector = StartupConnector(stage)
            val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
            val startup = async { startBoundSession(session, CompletableDeferred(Unit), CompletableDeferred()) }
            try {
                withTimeout(5.seconds) { connector.entered.await() }
                startup.cancel()
                withTimeout(5.seconds) { connector.closed.await() }
                withTimeout(5.seconds) { startup.join() }
                assertTrue(startup.isCancelled)
                withTimeout(5.seconds) { session.state.first { it is TerminalSessionState.Closed } }
                assertEquals(1, connector.closeCount.get())
            } finally {
                connector.release.countDown()
                withContext(NonCancellable + Dispatchers.IO) {
                    startup.cancelAndJoin()
                    session.close()
                }
            }
        }

    @Test
    fun `successful startup leaves the session owned by the host`() =
        runBlocking {
            val connector = StartupConnector(StartupStage.START)
            connector.release.countDown()
            val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
            try {
                assertTrue(startBoundSession(session, CompletableDeferred(Unit), CompletableDeferred()))
                assertEquals(TerminalSessionState.Running, session.state.value)
                assertEquals(0, connector.closeCount.get())
            } finally {
                withContext(Dispatchers.IO) { session.close() }
            }
            assertEquals(1, connector.closeCount.get())
        }

    @Test
    fun `startup failure preserves the original cause and closes the connector`() =
        runBlocking {
            val failure = IllegalStateException("startup failed")
            val connector = StartupConnector(StartupStage.START, failure)
            connector.release.countDown()
            val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
            try {
                val actual =
                    assertFailsWith<IllegalStateException> {
                        startBoundSession(session, CompletableDeferred(Unit), CompletableDeferred())
                    }
                assertEquals(failure.message, actual.message)
                val state = assertIs<TerminalSessionState.Closed>(session.state.value)
                assertSame(failure, state.event.failure)
                assertEquals(1, connector.closeCount.get())
            } finally {
                withContext(Dispatchers.IO) { session.close() }
            }
            assertEquals(1, connector.closeCount.get())
        }

    @Test
    fun `binding failure prevents connector startup`() =
        runBlocking {
            val failure = IllegalStateException("binding failed")
            val connector = StartupConnector(StartupStage.START)
            val session = TerminalSession.create(TerminalBuffers.create(80, 24), connector)
            val bound = CompletableDeferred<Unit>().apply { completeExceptionally(failure) }
            try {
                val actual = assertFailsWith<IllegalStateException> { startBoundSession(session, bound, CompletableDeferred()) }
                assertEquals(failure.message, actual.message)
                assertFalse(connector.entered.isCompleted)
                assertEquals(TerminalSessionState.Created, session.state.value)
            } finally {
                withContext(Dispatchers.IO) { session.close() }
            }
        }

    enum class StartupStage { RESIZE, START }

    private class StartupConnector(
        private val stage: StartupStage,
        private val failure: Throwable? = null,
    ) : TerminalConnector {
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val closeCount = AtomicInteger()

        override fun start(listener: TerminalConnectorListener) {
            when (stage) {
                StartupStage.RESIZE -> Unit
                StartupStage.START -> waitForRelease()
            }
            failure?.let { throw it }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            when (stage) {
                StartupStage.RESIZE -> waitForRelease()
                StartupStage.START -> Unit
            }
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun close() {
            closeCount.incrementAndGet()
            release.countDown()
            closed.complete(Unit)
        }

        private fun waitForRelease() {
            entered.complete(Unit)
            check(release.await(15, TimeUnit.SECONDS)) { "Startup did not receive a close request" }
        }
    }
}
