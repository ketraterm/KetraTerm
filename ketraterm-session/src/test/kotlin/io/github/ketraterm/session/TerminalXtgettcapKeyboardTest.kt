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
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalXtgettcapKeyboardTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Kotlin forwarding wrappers retain interception and current Backspace policy`(sendsBackspace: Boolean) =
        runTest {
            val underlying = TerminalBuffers.create(10, 3)
            var intercepted = 0
            val wrapped =
                object : TerminalRenderBuffer by underlying {
                    override fun queryTerminfo(rawPayload: String) {
                        intercepted++
                        underlying.queryTerminfo(rawPayload)
                    }
                }
            val policy = TerminalInputPolicy(backspacePolicy = if (sendsBackspace) BackspacePolicy.BACKSPACE else BackspacePolicy.DELETE)
            fixture(policy = policy, terminal = wrapped).use { f ->
                val expected = StringBuilder()
                for (current in listOf(sendsBackspace, !sendsBackspace)) {
                    f.session.setInputPolicy(
                        TerminalInputPolicy(backspacePolicy = if (current) BackspacePolicy.BACKSPACE else BackspacePolicy.DELETE),
                    )
                    f.receive(query("kb"))
                    f.session.encodeKey(TerminalKeyEvent(key = TerminalKey.BACKSPACE))
                    val value = if (current) "\b" else "\u007f"
                    expected.append(reply("kb", value)).append(value)
                    runCurrent()
                    assertEquals(expected.toString(), f.output())
                }
                assertEquals(2, intercepted)
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 1, 2, 3])
    fun `all aliases match actual key presses across cursor and modifier modes`(modifierLevel: Int) =
        runTest {
            fixture().use { f ->
                val expected = StringBuilder()
                val prefixes = resource("shift-cursor-prefixes.tsv").single { it[0].toInt() == modifierLevel }
                for (application in listOf(false, true, false)) {
                    f.receive(
                        "\u001b[?1" + (if (application) "h" else "l") +
                            (
                                if (modifierLevel <
                                    0
                                ) {
                                    "\u001b[>1n\u001b[>2n"
                                } else {
                                    "\u001b[>1;" + modifierLevel + "m\u001b[>2;" + modifierLevel + "m"
                                }
                            ),
                    )
                    assertEquals(modifierLevel, TerminalInputState.keyModifierOption(f.session.getInputModeBits(), 1))
                    assertEquals(modifierLevel, TerminalInputState.keyModifierOption(f.session.getInputModeBits(), 2))
                    val shiftedPrefix = unhex(prefixes[if (application) 2 else 1])
                    for (case in cases()) {
                        val value =
                            if (case.modifiers == TerminalModifiers.SHIFT && case.key != TerminalKey.TAB) {
                                shiftedPrefix + case.normal.last()
                            } else if (application) {
                                case.application
                            } else {
                                case.normal
                            }
                        for (name in listOf(case.terminfo, case.termcap)) {
                            f.receive(query(name))
                            f.session.encodeKey(TerminalKeyEvent(key = case.key, modifiers = case.modifiers))
                            expected.append(reply(name, value)).append(value)
                        }
                    }
                    runCurrent()
                    assertEquals(expected.toString(), f.output(), "application=" + application)
                }
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `Shift cursor discovery ignores other keyboard resource formats`(otherKeysLevel: Int) =
        runTest {
            fixture().use { f ->
                // Shift cursor keys belong to resource 1, independently of ordinary,
                // function and special-key reporting settings.
                f.receive("\u001b[>4;" + otherKeysLevel + "m\u001b[>2;4m\u001b[>7;4m")
                val shifted = cases().filter { it.modifiers == TerminalModifiers.SHIFT && it.key != TerminalKey.TAB }
                val expected = StringBuilder()
                for (case in shifted) {
                    for (name in listOf(case.terminfo, case.termcap)) {
                        f.receive(query(name))
                        f.session.encodeKey(TerminalKeyEvent(key = case.key, modifiers = case.modifiers))
                        expected.append(reply(name, case.normal)).append(case.normal)
                    }
                }
                runCurrent()
                assertEquals(4, shifted.size)
                assertEquals(expected.toString(), f.output())
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pinned Vim queries replay in source order with allowed and denied replies`(denied: Boolean) =
        runTest {
            fixture(denied = denied).use { f ->
                val keys = cases().associateBy { it.termcap }
                val requests = resource("vim-queries.tsv")
                val expected = StringBuilder()
                f.receive("\u001b[?1h")
                for (fields in requests) {
                    val name = fields[0]
                    // Replay the pinned producer bytes, not the test query helper.
                    for (byte in HexFormat.of().parseHex(fields[1])) {
                        f.session.onBytes(byteArrayOf(byte), 0, 1)
                    }
                    if (!denied) {
                        val value =
                            when (name) {
                                "Co" -> "256"
                                "PS" -> "\u001b[200~"
                                "PE" -> "\u001b[201~"
                                else -> keys[name]?.application
                            }
                        expected.append(reply(name, value))
                    }
                }
                runCurrent()
                assertEquals(37, requests.size)
                assertEquals(expected.toString(), f.output())
            }
        }

    @Test
    fun `Backspace discovery follows policy updates DECBKM and reset`() =
        runTest {
            fixture(policy = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE)).use { f ->
                val expected = StringBuilder()

                fun checkBackspace(value: String) {
                    f.receive(query("kb"))
                    f.session.encodeKey(TerminalKeyEvent(key = TerminalKey.BACKSPACE))
                    expected.append(reply("kb", value)).append(value)
                    runCurrent()
                    assertEquals(expected.toString(), f.output())
                }
                checkBackspace("\b")
                f.session.setInputPolicy(TerminalInputPolicy(backspacePolicy = BackspacePolicy.DELETE))
                checkBackspace("\u007f")
                f.receive("\u001b[?67h")
                checkBackspace("\b")
                f.session.setInputPolicy(TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE))
                f.receive("\u001b[?67l")
                checkBackspace("\u007f")
                f.receive("\u001b[!p")
                checkBackspace("\b") // DECSTR restores the current host default.
                f.receive("\u001b[?67l")
                checkBackspace("\u007f")
                f.receive("\u001bc")
                checkBackspace("\b") // RIS restores the current host default.
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 7])
    fun `extended resources fail only their own key family and recover`(resource: Int) =
        runTest {
            fixture().use { f ->
                val expected = StringBuilder()
                val keys = cases()
                val affected =
                    keys.filter { case ->
                        when (resource) {
                            1 ->
                                case.key in
                                    listOf(
                                        TerminalKey.UP,
                                        TerminalKey.DOWN,
                                        TerminalKey.LEFT,
                                        TerminalKey.RIGHT,
                                        TerminalKey.HOME,
                                        TerminalKey.END,
                                        TerminalKey.INSERT,
                                        TerminalKey.DELETE,
                                        TerminalKey.PAGE_UP,
                                        TerminalKey.PAGE_DOWN,
                                    )
                            2 -> case.key.name.startsWith("F")
                            else -> case.key == TerminalKey.BACKSPACE || case.key == TerminalKey.TAB
                        }
                    }
                for (format in listOf(0, 1)) {
                    f.receive("\u001b[>" + resource + ";" + format + "f\u001b[>" + resource + ";4m")
                    assertEquals(format, TerminalInputState.keyFormatOption(f.session.getInputModeBits(), resource))
                    for (case in keys) {
                        f.receive(query(case.terminfo))
                        expected.append(reply(case.terminfo, if (case in affected) null else case.normal))
                    }
                    f.receive(query("Co"))
                    expected.append(reply("Co", "256"))
                    runCurrent()
                    assertEquals(expected.toString(), f.output())
                }
                f.receive("\u001b[>" + resource + "m")
                // Re-query and press this resource's own keys: checking Up
                // would not prove that function or special keys recovered.
                for (case in affected) {
                    f.receive(query(case.terminfo))
                    f.session.encodeKey(TerminalKeyEvent(key = case.key, modifiers = case.modifiers))
                    expected.append(reply(case.terminfo, case.normal)).append(case.normal)
                }
                runCurrent()
                assertEquals(expected.toString(), f.output())
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `modifyOtherKeys affects only the relevant Backspace and backtab entries`(level: Int) =
        runTest {
            fixture().use { f ->
                f.receive("\u001b[>4;" + level + "m" + query("kb") + query("kB") + query("ku"))
                runCurrent()
                assertEquals(
                    reply("kb", if (level == 3) null else "\u007f") +
                        reply("kB", if (level >= 2) null else "\u001b[Z") + reply("ku", "\u001b[A"),
                    f.output(),
                )
            }
        }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 4, 8, 16])
    fun `Kitty modes decline legacy key discovery and retain output capabilities`(flags: Int) =
        runTest {
            fixture().use { f ->
                val expected = StringBuilder()
                f.receive("\u001b[=" + flags + "u")
                assertEquals(flags, TerminalInputState.kittyKeyboardFlags(f.session.getInputModeBits()))
                for (case in cases()) {
                    f.receive(query(case.terminfo))
                    expected.append(reply(case.terminfo, null))
                }
                f.receive(query("Co") + "\u001b[=0u" + query("ku"))
                expected.append(reply("Co", "256")).append(reply("ku", "\u001b[A"))
                runCurrent()
                assertEquals(expected.toString(), f.output())
            }
        }

    private fun TestScope.fixture(
        denied: Boolean = false,
        policy: TerminalInputPolicy = TerminalInputPolicy(),
        terminal: TerminalRenderBuffer = TerminalBuffers.create(10, 3),
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connector = MockConnector()
        val session =
            TerminalSession.create(
                terminal,
                connector,
                inputPolicy = policy,
                kittyKeyboardSupportedFlags = KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK,
                hostPolicy = HostPolicy(terminalResponsePolicy = if (denied) HostControlPolicy.DENY else HostControlPolicy.ALLOW),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            )
        session.start()
        return Fixture(session, connector)
    }

    private class Fixture(
        val session: TerminalSession,
        val connector: MockConnector,
    ) : AutoCloseable {
        fun receive(text: String) {
            val bytes = text.encodeToByteArray()
            session.onBytes(bytes, 0, bytes.size)
        }

        fun output(): String = connector.writtenBytes.decodeToString()

        override fun close() = session.close()
    }

    private data class Case(
        val terminfo: String,
        val termcap: String,
        val key: TerminalKey,
        val modifiers: Int,
        val normal: String,
        val application: String,
    )

    private fun cases(): List<Case> =
        resource("keys.tsv")
            .map { f ->
                Case(
                    f[0],
                    f[1],
                    TerminalKey.valueOf(f[2]),
                    if (f[3] == "SHIFT") TerminalModifiers.SHIFT else TerminalModifiers.NONE,
                    unhex(f[4]),
                    unhex(f[5]),
                )
            }.also { assertEquals(28, it.size) }

    private fun resource(name: String): List<List<String>> =
        checkNotNull(javaClass.getResource("/xtgettcap/" + name))
            .readText()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("# ") }
            .map { it.split('\t') }
            .toList()
            .also { assertTrue(it.isNotEmpty()) }

    private fun query(name: String): String = "\u001bP+q" + hex(name) + "\u001b\\"

    private fun reply(
        name: String,
        value: String?,
    ): String =
        "\u001bP" + (if (value == null) "0" else "1") + "+r" + hex(name) +
            (if (value == null) "" else "=" + hex(value)) + "\u001b\\"

    private fun hex(value: String): String = HexFormat.of().formatHex(value.encodeToByteArray())

    private fun unhex(value: String): String = HexFormat.of().parseHex(value).decodeToString()
}
