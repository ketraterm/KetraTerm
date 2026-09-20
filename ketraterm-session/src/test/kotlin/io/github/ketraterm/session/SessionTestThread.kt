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

import java.lang.management.ManagementFactory
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal const val SESSION_THREAD_TIMEOUT_SECONDS = 10L

/** Platform threads expose the monitor ownership these serialization tests must prove. */
internal class SessionTestThread(
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

    fun awaitBlockedBy(owner: SessionTestThread) = awaitBlockedBy(owner.thread)

    fun awaitBlockedBy(owner: Thread) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SESSION_THREAD_TIMEOUT_SECONDS)
        while (true) {
            val info = threads.getThreadInfo(thread.threadId())
            if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == owner.threadId()) return
            if (result.isDone) {
                awaitCompletion()
                error("${thread.name} completed without contending with ${owner.name}")
            }
            check(System.nanoTime() < deadline) {
                "${thread.name} did not contend with ${owner.name}; observed $info"
            }
            Thread.onSpinWait()
        }
    }

    fun awaitCompletion() {
        result.get(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun close() {
        if (!result.isDone) result.cancel(true)
        thread.join(TimeUnit.SECONDS.toMillis(SESSION_THREAD_TIMEOUT_SECONDS))
        check(!thread.isAlive) { "${thread.name} did not terminate after cancellation" }
        if (!result.isCancelled) result.get()
    }
}
