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
package consumer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalLine
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.spi.TerminalCommandSink

private class KotlinSink(
    val output: JavaConsumer.Sink,
) : TerminalCommandSink by output {
    var bells = 0

    override fun bell() {
        bells++
    }
}

private class KotlinEvents : HostEventSink {
    var title = ""

    override fun bell() = Unit

    override fun iconTitleChanged(title: String) = Unit

    override fun windowTitleChanged(title: String) {
        this.title = title
    }

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) = Unit
}

private class KotlinLine : TerminalLine {
    override val width = 1

    override fun getCodepoint(col: Int) = if (col == 0) 'K'.code else 0
}

fun main() {
    JavaConsumer.verify()
    val sink = KotlinSink(JavaConsumer.Sink())
    val parser = TerminalParsers.create(sink)
    for (byte in "Kotlin\u0007\u001b]52;c;?\u0007".encodeToByteArray()) parser.accept(byteArrayOf(byte))
    parser.endOfInput()
    check(sink.output.text.toString() == "Kotlin" && sink.bells == 1)
    val events = KotlinEvents()
    val buffer = TerminalBuffers.create(8, 2, 0)
    TerminalParsers.create(HostCommandAdapter(buffer, events)).accept("\u001b]2;Kotlin title\u0007".encodeToByteArray())
    check(events.title == "Kotlin title")
    // Optional observers inherit library defaults; required commands remain concrete.
    events.currentWorkingDirectoryChanged("file:///tmp")
    val line = KotlinLine()
    check(line.getCodepoint(0) == 'K'.code && !line.isCluster(0) && line.readCluster(0, IntArray(0)) == 0)
}
