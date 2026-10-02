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

import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-scoped, read-only access for a shell producer that derives metadata from
 * terminal output. Frames and published caches are borrowed only for the callback;
 * never mutate or retain them. No callback may block on UI work or close the session.
 */
public class TerminalShellIntegrationContext internal constructor(
    private val mutationLock: Any,
    private val reader: TerminalRenderFrameReader,
    private val publisher: TerminalRenderPublisher,
    /** Successfully published frame revisions; -1 before the first frame. */
    public val renderGeneration: StateFlow<Long>,
) : TerminalRenderFrameReader {
    /** Serializes a short compound context/frame read with parser and grid mutation. */
    public fun <T> withTerminalState(action: () -> T): T = synchronized(mutationLock, action)

    /** Borrows the most recent copied frame without acquiring terminal mutation serialization. */
    public fun <T> readPublishedFrame(action: (TerminalRenderCache) -> T): T? = publisher.readCurrent(action)

    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer): Unit =
        synchronized(mutationLock) { reader.readRenderFrame(consumer) }

    override fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: TerminalRenderFrameConsumer,
    ): Unit = synchronized(mutationLock) { reader.readRenderFrame(scrollbackOffset, consumer) }

    override fun readRenderFrame(
        scrollbackOffset: Int,
        viewportRows: Int,
        consumer: TerminalRenderFrameConsumer,
    ): Unit = synchronized(mutationLock) { reader.readRenderFrame(scrollbackOffset, viewportRows, consumer) }

    override fun readRenderFrameForAbsoluteRange(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        consumer: TerminalRenderFrameConsumer,
    ): Unit = synchronized(mutationLock) { reader.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow, consumer) }
}
