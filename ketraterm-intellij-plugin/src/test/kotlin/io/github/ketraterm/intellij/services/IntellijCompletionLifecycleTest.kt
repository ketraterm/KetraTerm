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
package io.github.ketraterm.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class IntellijCompletionLifecycleTest {
    @Test
    fun `close waits for in-flight creation and rejects every later creation`() {
        val lifecycle = IntellijCompletionLifecycle()
        val enteredCreation = CountDownLatch(1)
        val releaseCreation = CountDownLatch(1)
        val completed = ConcurrentLinkedQueue<String>()
        MonitorThread("completion-creation-test") {
            lifecycle.requireOpen {
                enteredCreation.countDown()
                releaseCreation.await()
                completed.add("creation")
            }
        }.use { creation ->
            try {
                assertTrue(enteredCreation.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                MonitorThread("completion-close-test") {
                    assertTrue(lifecycle.beginClose())
                    completed.add("close")
                }.use { close ->
                    try {
                        close.awaitBlockedBy(creation)
                        assertTrue(completed.isEmpty())
                    } finally {
                        releaseCreation.countDown()
                    }
                    creation.awaitCompletion()
                    close.awaitCompletion()
                }
                assertEquals(listOf("creation", "close"), completed.toList())
                assertFalse(lifecycle.beginClose())
                assertThrows(IllegalStateException::class.java) { assertTrue(lifecycle.requireOpen { true }) }
            } finally {
                releaseCreation.countDown()
            }
        }
    }

    /** Uses actual JVM monitor ownership to distinguish exclusion from a thread that has not run yet. */
    private class MonitorThread(
        name: String,
        action: () -> Unit,
    ) : AutoCloseable {
        private val result = FutureTask(action)
        private val thread =
            Thread
                .ofPlatform()
                .daemon()
                .name(name)
                .start(result)

        fun awaitBlockedBy(owner: MonitorThread) {
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (true) {
                val info = threads.getThreadInfo(thread.threadId())
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.thread.threadId()) return
                if (result.isDone) {
                    awaitCompletion()
                    error("${thread.name} completed without contending with ${owner.thread.name}")
                }
                check(System.nanoTime() < deadline) {
                    "${thread.name} did not contend with ${owner.thread.name}; observed $info"
                }
                Thread.onSpinWait()
            }
        }

        fun awaitCompletion() {
            result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        override fun close() {
            if (!result.isDone) result.cancel(true)
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            check(!thread.isAlive) { "${thread.name} did not terminate after cancellation" }
            if (!result.isCancelled) result.get()
        }
    }

    private companion object {
        private const val TIMEOUT_SECONDS = 10L
    }
}
