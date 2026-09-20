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
package io.github.ketraterm.pty

import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PtyForegroundProcessDetectorTest {
    @Test
    fun `newest live descendant wins by start time rather than pid or iteration order`() {
        val newest = TestHandle(2, Instant.ofEpochSecond(30))
        val root =
            TestHandle(
                1,
                descendants =
                    listOf(
                        newest,
                        TestHandle(100, Instant.ofEpochSecond(10)),
                        TestHandle(200, Instant.ofEpochSecond(40), alive = false),
                    ),
            )
        assertSame(newest, PtyForegroundProcessDetector.newestDescendant(root))
        assertTrue(root.streamClosed)
    }

    @Test
    fun `equal start times use a deterministic pid tie break`() {
        val lower = TestHandle(2)
        val higher = TestHandle(3)
        for (order in listOf(listOf(lower, higher), listOf(higher, lower))) {
            assertSame(higher, PtyForegroundProcessDetector.newestDescendant(TestHandle(1, descendants = order)))
        }
    }

    @Test
    fun `no live children returns the shell and an exited shell returns no name`() {
        val root = TestHandle(1, descendants = listOf(TestHandle(2, alive = false)))
        assertSame(root, PtyForegroundProcessDetector.newestDescendant(root))
        assertNull(PtyForegroundProcessDetector.newestDescendant(TestHandle(1, alive = false)))
    }

    @Test
    fun `missing start time makes detection unavailable and closes the snapshot`() {
        val root = TestHandle(1, descendants = listOf(TestHandle(2), TestHandle(3, started = null)))
        assertNull(PtyForegroundProcessDetector.newestDescendant(root))
        assertTrue(root.streamClosed)
    }

    @Test
    fun `oversized trees are rejected rather than choosing from a partial snapshot`() {
        val children = (2L..258L).map { TestHandle(it) }
        val atLimit = TestHandle(1, descendants = children.take(256))
        assertSame(children[255], PtyForegroundProcessDetector.newestDescendant(atLimit))
        val oversized = TestHandle(1, descendants = children)
        assertNull(PtyForegroundProcessDetector.newestDescendant(oversized))
        assertTrue(oversized.streamClosed)
    }

    private class TestHandle(
        private val id: Long,
        private val started: Instant? = Instant.EPOCH,
        private val alive: Boolean = true,
        private val descendants: List<ProcessHandle> = emptyList(),
    ) : ProcessHandle {
        var streamClosed = false
            private set

        override fun pid() = id

        override fun isAlive() = alive

        override fun descendants(): Stream<ProcessHandle> = descendants.stream().onClose { streamClosed = true }

        override fun info(): ProcessHandle.Info =
            object : ProcessHandle.Info {
                override fun startInstant(): Optional<Instant> = Optional.ofNullable(started)

                override fun command(): Optional<String> = Optional.empty()

                override fun commandLine(): Optional<String> = Optional.empty()

                override fun arguments(): Optional<Array<String>> = Optional.empty()

                override fun totalCpuDuration(): Optional<Duration> = Optional.empty()

                override fun user(): Optional<String> = Optional.empty()
            }

        override fun compareTo(other: ProcessHandle): Int = id.compareTo(other.pid())

        override fun parent(): Optional<ProcessHandle> = error("Not used by detection")

        override fun children(): Stream<ProcessHandle> = error("Not used by detection")

        override fun onExit(): CompletableFuture<ProcessHandle> = error("Not used by detection")

        override fun supportsNormalTermination(): Boolean = error("Not used by detection")

        override fun destroy(): Boolean = error("Detection must not terminate processes")

        override fun destroyForcibly(): Boolean = error("Detection must not terminate processes")
    }
}
