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
package io.github.ketraterm.parser.api

import io.github.ketraterm.parser.impl.TerminalParser
import io.github.ketraterm.parser.spi.TerminalCommandSink

/**
 * Factory for terminal output parsers.
 */
object TerminalParsers {
    /**
     * Creates a new instance of [TerminalOutputParser] that routes parsed
     * commands to the specified [sink].
     *
     * @param sink The command sink where parsed terminal commands will be delivered.
     * @return A newly initialized [TerminalOutputParser] instance.
     */
    @JvmStatic
    fun create(sink: TerminalCommandSink): TerminalOutputParser = TerminalParser(sink)
}
