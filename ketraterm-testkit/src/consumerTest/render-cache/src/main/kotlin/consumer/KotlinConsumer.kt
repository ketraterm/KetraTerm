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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class KotlinFrame(
    private val value: Int,
) : TerminalRenderFrame,
    TerminalRenderFrameReader {
    override val columns = 1
    override val rows = 1
    override val frameGeneration = value.toLong()
    override val structureGeneration = 1L
    override val activeBuffer = TerminalRenderBufferKind.PRIMARY
    override val cursor = TerminalRenderCursor(0, 0, true, false, TerminalRenderCursorShape.BLOCK, value.toLong())

    override fun lineGeneration(row: Int) = frameGeneration

    override fun lineWrapped(row: Int) = false

    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
        consumer.accept(this)
    }

    override fun copyLine(
        row: Int,
        codeWords: IntArray,
        codeOffset: Int,
        attrWords: LongArray,
        attrOffset: Int,
        flags: IntArray,
        flagOffset: Int,
        extraAttrWords: LongArray?,
        extraAttrOffset: Int,
        hyperlinkIds: IntArray?,
        hyperlinkOffset: Int,
        clusterSink: TerminalRenderClusterSink?,
        clusterDataSink: TerminalRenderClusterDataSink?,
    ) {
        codeWords[codeOffset] = value
        attrWords[attrOffset] = 0
        flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
        extraAttrWords?.set(extraAttrOffset, 0)
        hyperlinkIds?.set(hyperlinkOffset, 0)
    }
}

fun main() {
    JavaConsumer.verify()
    val frame = KotlinFrame('K'.code)
    check(frame.contentGeneration == frame.frameGeneration && frame.historyContentGeneration == frame.contentGeneration)
    frame.readRenderFrame(100, Int.MAX_VALUE) { check(it.rows == 1 && it.scrollbackOffset == 0) }
    val publisher = TerminalRenderPublisher(1, 1)
    check(publisher.readCurrent<Unit> { error("No frame callback") } == null)
    publisher.updateAndPublish(frame)
    val failure = IllegalStateException("callback failure")
    try {
        publisher.readCurrent { throw failure }
        error("Expected callback failure")
    } catch (
        caught: IllegalStateException,
    ) {
        check(caught === failure)
    }

    val entered = CountDownLatch(2)
    val releaseFirst = CountDownLatch(1)
    val releaseSecond = CountDownLatch(1)
    val releaseThird = CountDownLatch(1)
    val releaseFourth = CountDownLatch(1)
    val workers = Executors.newFixedThreadPool(3)
    try {
        val first =
            workers.submit {
                try {
                    publisher.readCurrent { cache ->
                        check(cache.codeWords[0] == 'K'.code)
                        entered.countDown()
                        releaseFirst.await()
                        check(cache.codeWords[0] == 'K'.code)
                        throw failure
                    }
                    error("Expected pinned callback failure")
                } catch (caught: IllegalStateException) {
                    check(caught === failure)
                }
            }
        val second =
            workers.submit {
                JavaConsumer.read(publisher) { cache ->
                    entered.countDown()
                    releaseSecond.await()
                    check(cache.codeWords[0] == 'K'.code)
                }
            }
        check(entered.await(10, TimeUnit.SECONDS)) { "Both retained readers must hold the original frame" }
        repeat(64) { publisher.updateAndPublish(JavaConsumer.Frame('A'.code + it)) }
        releaseFirst.countDown()
        first.get(10, TimeUnit.SECONDS)
        // A single release must leave the second reader's frame pinned.
        repeat(64) { publisher.updateAndPublish(KotlinFrame('a'.code + it)) }
        releaseSecond.countDown()
        second.get(10, TimeUnit.SECONDS)
        repeat(64) { publisher.updateAndPublish(JavaConsumer.Frame('Z'.code)) }
        publisher.readCurrent { check(it.codeWords[0] == 'Z'.code && it.frameGeneration == 'Z'.code.toLong()) }
        // Pin the other two physical buffers. Publication now requires every earlier lease to be balanced.
        val thirdEntered = CountDownLatch(1)
        val third =
            workers.submit {
                publisher.readCurrent { cache ->
                    thirdEntered.countDown()
                    releaseThird.await()
                    check(cache.codeWords[0] == 'Z'.code)
                }
            }
        check(thirdEntered.await(10, TimeUnit.SECONDS))
        publisher.updateAndPublish(JavaConsumer.Frame('Y'.code))
        val fourthEntered = CountDownLatch(1)
        val fourth =
            workers.submit {
                JavaConsumer.read(publisher) { cache ->
                    fourthEntered.countDown()
                    releaseFourth.await()
                    check(cache.codeWords[0] == 'Y'.code)
                }
            }
        check(fourthEntered.await(10, TimeUnit.SECONDS))
        workers.submit { publisher.updateAndPublish(KotlinFrame('X'.code)) }.get(10, TimeUnit.SECONDS)
        publisher.readCurrent { check(it.codeWords[0] == 'X'.code) }
        releaseThird.countDown()
        releaseFourth.countDown()
        third.get(10, TimeUnit.SECONDS)
        fourth.get(10, TimeUnit.SECONDS)
    } finally {
        releaseFirst.countDown()
        releaseSecond.countDown()
        releaseThird.countDown()
        releaseFourth.countDown()
        workers.shutdownNow()
        check(workers.awaitTermination(10, TimeUnit.SECONDS)) { "Retained reader workers did not stop" }
    }
}
