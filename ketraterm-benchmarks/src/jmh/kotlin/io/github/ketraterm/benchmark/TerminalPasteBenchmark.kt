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
package io.github.ketraterm.benchmark

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Measures paste encoding through the production session and UTF-8 writer, excluding transport I/O. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class TerminalPasteBenchmark {
    @Param("plain", "protected", "filtered", "newlines", "unicode")
    var workload: String = ""

    @Param("1024", "16384")
    var codeUnits: Int = 0

    private lateinit var session: TerminalSession
    private lateinit var event: TerminalPasteEvent
    private val connector = ChecksumConnector()

    @Setup(Level.Trial)
    open fun setup() {
        val unit =
            when (workload) {
                "plain" -> "ordinary text "
                "protected" -> "text\u001b[201~\u0003\u009b201~\r\n"
                "filtered" -> "text\u0000\u001b[201~\u0003\u009b201~\r\n"
                "newlines" -> "text\r\ntext\ntext\r"
                "unicode" -> "\u041b201~ e\u0301 \ud83d\ude00 "
                else -> error("unknown workload: $workload")
            }
        event = TerminalPasteEvent(unit.repeat((codeUnits + unit.length - 1) / unit.length).take(codeUnits))
        session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 80, height = 24),
                connector = connector,
                inputPolicy =
                    TerminalInputPolicy(
                        pasteControlPolicy =
                            if (workload ==
                                "filtered"
                            ) {
                                PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF
                            } else {
                                PasteControlPolicy.PRESERVE
                            },
                        pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN,
                    ),
            )
        session.start(80, 24)
        if (workload != "newlines") {
            val enableBracketedPaste = "\u001b[?2004h".encodeToByteArray()
            session.onBytes(enableBracketedPaste, 0, enableBracketedPaste.size)
        }
    }

    @TearDown(Level.Trial)
    open fun tearDown() {
        session.close()
    }

    @Benchmark
    open fun paste(): Int {
        connector.checksum = 0
        session.encodePaste(event)
        return connector.checksum
    }

    private class ChecksumConnector : TerminalConnector {
        var checksum: Int = 0

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var sum = checksum
            for (index in offset until offset + length) sum += bytes[index].toInt() and 0xff
            checksum = sum
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }
}
