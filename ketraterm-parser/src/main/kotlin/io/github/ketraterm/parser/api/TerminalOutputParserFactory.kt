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

import io.github.ketraterm.parser.spi.TerminalCommandSink

/**
 * Creates a parser bound to a host's assembled command sink and collection policy.
 *
 * Construction must not emit commands or start jobs.
 * Return an exclusively owned parser; forward its reset and end-of-input lifecycle
 * when decorating another parser. Session assembly supplies its normal sink, so
 * clipboard, resize, shell metadata, and startup coordination remain installed.
 */
public fun interface TerminalOutputParserFactory {
    /**
     * @param sink authoritative semantic destination; route parsed commands here.
     * @param clipboardWriteLimitBytes current permitted decoded clipboard-write budget,
     *   sampled at write start as defined by [TerminalParsers.create].
     * @return a new parser obeying [TerminalOutputParser]'s serialization contract.
     */
    public fun create(
        sink: TerminalCommandSink,
        clipboardWriteLimitBytes: () -> Int,
    ): TerminalOutputParser
}
