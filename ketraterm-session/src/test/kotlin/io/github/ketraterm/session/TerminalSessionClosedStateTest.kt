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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionClosedStateTest {
    @ParameterizedTest
    @ValueSource(strings = ["local", "remote", "unstarted"])
    fun `closed sessions freeze setters and reject resize while retaining reads`(closure: String) =
        runTest {
            val core = TerminalBuffers.create(10, 3)
            var widthPolicyCalls = 0
            var inputPolicyCalls = 0
            val terminal =
                object : TerminalBuffer by core {
                    override fun setTreatAmbiguousAsWide(enabled: Boolean) {
                        widthPolicyCalls++
                        core.setTreatAmbiguousAsWide(enabled)
                    }
                }
            val adapter = HostCommandAdapter(terminal)
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession(
                    terminal,
                    TerminalRenderPublisher(10, 3),
                    core,
                    core,
                    connector,
                    TerminalParsers.create(adapter),
                    inputEncoderFactory =
                        TerminalInputEncoderFactory { modes, output, policy ->
                            val encoder = TerminalInputEncoders.create(modes, output, policy)
                            object : TerminalInputEncoder by encoder {
                                override fun setInputPolicy(policy: TerminalInputPolicy) {
                                    inputPolicyCalls++
                                    encoder.setInputPolicy(policy)
                                }
                            }
                        },
                    hostCommandAdapter = adapter,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            if (closure != "unstarted") session.start(10, 3)
            if (closure == "remote") connector.simulateClosed(0) else session.close()
            val palette = session.palette
            val generation = session.renderGeneration.value
            val initialPolicy = adapter.currentPolicy
            val resizeCalls = connector.resizeCalls.toList()
            widthPolicyCalls = 0
            inputPolicyCalls = 0

            session.setThemePalette(TerminalColorPalette(isDark = false))
            session.setCursorShape(TerminalRenderCursorShape.BAR)
            session.setTreatAmbiguousAsWide(true)
            session.setHostPolicy(HostPolicy(hyperlinkPolicy = HostControlPolicy.DENY))
            session.setInputPolicy(TerminalInputPolicy())
            session.setPasteControlPolicy(PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF)
            session.setWindowMinimized(true)
            session.requestRender(2, 5)

            assertAll(
                { assertEquals(0, widthPolicyCalls) },
                { assertEquals(0, inputPolicyCalls) },
                { assertSame(palette, session.palette) },
                { assertSame(initialPolicy, adapter.currentPolicy) },
                { assertEquals(generation, session.renderGeneration.value) },
                { session.readRenderFrame { assertEquals(TerminalRenderCursorShape.BLOCK, it.cursor.shape) } },
                { assertThrows(IllegalStateException::class.java) { session.resize(20, 6) } },
                { assertThrows(IllegalStateException::class.java) { session.resizeViewport(20, 6) } },
            )
            assertEquals(resizeCalls, connector.resizeCalls)
            session.readRenderFrame {
                assertEquals(10, it.columns)
                assertEquals(3, it.rows)
            }
        }
}
