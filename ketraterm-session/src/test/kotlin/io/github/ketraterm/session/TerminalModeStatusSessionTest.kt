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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.TerminalHostModeCapability
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalModeStatusSessionTest {
    @Test
    fun waitingPolicyUpdateDoesNotPreventIngressFromUnblockingPaste() =
        runTest {
            val transport = MockConnector()
            val writeEntered = CountDownLatch(1)
            val ingressCompleted = CountDownLatch(1)
            val connector =
                object : TerminalConnector by transport {
                    private var gateNextWrite = true

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        if (gateNextWrite) {
                            gateNextWrite = false
                            writeEntered.countDown()
                            ingressCompleted.await()
                        }
                        transport.write(bytes, offset, length)
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val backspace = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputPolicy = backspace,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    val writer =
                        SessionTestThread("mode-status-blocked-paste") {
                            session.encodePaste(TerminalPasteEvent("paste"))
                        }
                    var policyUpdate: SessionTestThread? = null
                    var ingress: SessionTestThread? = null
                    try {
                        assertTrue(
                            writeEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                            "paste did not enter connector.write",
                        )
                        policyUpdate =
                            SessionTestThread("mode-status-policy-update") {
                                session.setInputPolicy(backspace.copy(backspacePolicy = BackspacePolicy.DELETE))
                            }
                        // Prove the policy update reached the writer's monitor before ingress starts.
                        policyUpdate.awaitBlockedBy(writer)
                        ingress =
                            SessionTestThread("mode-status-unblocking-ingress") {
                                transport.feedFromHost("ready".encodeToByteArray())
                                ingressCompleted.countDown()
                            }
                        ingress.awaitCompletion()
                        writer.awaitCompletion()
                        policyUpdate.awaitCompletion()

                        assertEquals("ready", session.terminal.getLineAsString(0))
                        transport.feedFromHost("\u001B[?67\$p".encodeToByteArray())
                        session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                        assertEquals("paste\u001B[?67;2\$y\u007F", transport.writtenBytes.decodeToString())
                    } finally {
                        // Break the simulated pipe cycle even if the regression fails.
                        ingressCompleted.countDown()
                        writer.close()
                        policyUpdate?.close()
                        ingress?.close()
                    }
                }
        }

    @Test
    fun backarrowReportsAgreeWithInputAcrossPolicyChangesOverridesAndResets() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val backspace = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE)
            val delete = backspace.copy(backspacePolicy = BackspacePolicy.DELETE)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputPolicy = backspace,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)

                    fun queryAndType() {
                        connector.feedFromHost("\u001B[?67\$p".encodeToByteArray())
                        session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                    }
                    queryAndType()
                    session.setInputPolicy(delete)
                    queryAndType()
                    connector.feedFromHost("\u001B[?67h".encodeToByteArray())
                    queryAndType()
                    session.setInputPolicy(backspace)
                    connector.feedFromHost("\u001B[?67l".encodeToByteArray())
                    queryAndType()
                    connector.feedFromHost("\u001B[!p".encodeToByteArray())
                    queryAndType()
                    session.setInputPolicy(delete)
                    connector.feedFromHost("\u001B[?67h\u001Bc".encodeToByteArray())
                    queryAndType()
                    assertEquals(
                        "\u001B[?67;1\$y\u0008\u001B[?67;2\$y\u007F\u001B[?67;1\$y\u0008" +
                            "\u001B[?67;2\$y\u007F\u001B[?67;1\$y\u0008\u001B[?67;2\$y\u007F",
                        connector.writtenBytes.decodeToString(),
                    )
                }
        }

    @Test
    fun synchronizedOutputReportsTimeoutAndLiveResponsePolicy() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001B[?2026h\u001B[?2026\$p".encodeToByteArray())
                    runCurrent()
                    advanceTimeBy(100.milliseconds)
                    runCurrent()
                    connector.feedFromHost("\u001B[?2026\$p".encodeToByteArray())
                    session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
                    connector.feedFromHost("\u001B[?2026\$p\u001B[?2031\$p".encodeToByteArray())
                    session.setHostPolicy(HostPolicy())
                    connector.feedFromHost("\u001B[?2031\$p".encodeToByteArray())
                    assertEquals(
                        "\u001B[?2026;1\$y\u001B[?2026;2\$y\u001B[?2031;0\$y",
                        connector.writtenBytes.decodeToString(),
                    )
                }
        }

    @Test
    fun hostCapabilityConfigurationReachesTheResponseChannel() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            for (capabilities in listOf(0, TerminalHostModeCapability.POP_ON_BELL)) {
                val connector = MockConnector()
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        workerDispatcher = dispatcher,
                        ioDispatcher = dispatcher,
                        modeReportCapabilities = capabilities,
                    ).use { session ->
                        session.start(10, 3)
                        connector.feedFromHost("\u001B[?1042h\u001B[?1043h\u001B[?1042\$p\u001B[?1043\$p".encodeToByteArray())
                        assertEquals(
                            if (capabilities == 0) {
                                "\u001B[?1042;0\$y\u001B[?1043;0\$y"
                            } else {
                                "\u001B[?1042;0\$y\u001B[?1043;1\$y"
                            },
                            connector.writtenBytes.decodeToString(),
                        )
                    }
            }
        }
}
