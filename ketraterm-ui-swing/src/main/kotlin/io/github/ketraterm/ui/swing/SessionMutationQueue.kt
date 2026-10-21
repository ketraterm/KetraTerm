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
package io.github.ketraterm.ui.swing

import io.github.ketraterm.session.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Serializes captured view mutations without making the EDT wait for session admission. */
internal class SessionMutationQueue(
    scope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    private val uiDispatcher: CoroutineDispatcher,
) {
    private val pending = ArrayDeque<Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var lastSubmitted: Request? = null

    // Completed worker actions still own captured state until the EDT delivers their result.
    private var outstanding = 0

    init {
        scope.launch(workerDispatcher) {
            for (signal in wake) {
                while (true) {
                    val request = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                    var install: (() -> Unit)? = null
                    var failure: Exception? = null
                    var applied = false
                    try {
                        install =
                            request.session.withMutationAdmission(request::admitIfCurrent) {
                                applied = true
                                request.apply()
                            }
                    } catch (exception: Exception) {
                        failure = exception
                    }
                    withContext(NonCancellable + uiDispatcher) {
                        synchronized(pending) {
                            outstanding--
                            if (lastSubmitted === request) lastSubmitted = null
                        }
                        val current = request.isCurrent()
                        var completionFailure: Throwable? = failure
                        try {
                            if (current && completionFailure == null) install?.invoke()
                        } catch (exception: Exception) {
                            completionFailure = exception
                        }
                        request.completeSafely(
                            completionFailure
                                ?: if (!applied || !current) CancellationException("Terminal view mutation superseded") else null,
                        )
                    }
                }
            }
        }
    }

    /**
     * Accepts captured work on the EDT. [isCurrent] must be safe on either thread;
     * [apply] runs after session admission and returns an optional EDT installer.
     * [complete] runs once on the EDT, including cancellation and installation failure.
     * Only adjacent geometry requests may replace one another; policy/settings retain their order.
     */
    fun submit(
        session: TerminalSession,
        isCurrent: () -> Boolean,
        geometry: Boolean = false,
        apply: () -> (() -> Unit)?,
        complete: (Throwable?) -> Unit,
    ) {
        val superseded =
            synchronized(pending) {
                check(!closed) { "Terminal view mutation queue is closed" }
                val previous = if (geometry && pending.lastOrNull()?.geometry == true) pending.last() else null
                check(outstanding - (if (previous == null) 0 else 1) < CAPACITY) { "Terminal view mutation queue is full" }
                if (geometry && lastSubmitted?.geometry == true) lastSubmitted?.supersede()
                if (previous != null) {
                    pending.removeLast()
                    outstanding--
                }
                val request = Request(session, isCurrent, geometry, apply, complete)
                pending.addLast(request)
                outstanding++
                lastSubmitted = request
                previous
            }
        wake.trySend(Unit)
        superseded?.completeSafely(CancellationException("Terminal view geometry superseded"))
    }

    /** Called on the EDT after invalidating the binding, before cancelling the owning scope. */
    fun close() {
        val abandoned =
            synchronized(pending) {
                closed = true
                lastSubmitted?.supersede()
                lastSubmitted = null
                outstanding -= pending.size
                pending.toList().also { pending.clear() }
            }
        wake.close()
        var failure: Throwable? = null
        for (request in abandoned) {
            try {
                request.complete(CancellationException("Terminal view disposed"))
            } catch (next: Throwable) {
                failure = preserveSwingFailure(failure, next)
            }
        }
        failure?.let { throw it }
    }

    private class Request(
        val session: TerminalSession,
        val bindingIsCurrent: () -> Boolean,
        val geometry: Boolean,
        val apply: () -> (() -> Unit)?,
        val complete: (Throwable?) -> Unit,
    ) {
        private val state = AtomicInteger(PENDING)

        fun admitIfCurrent(): Boolean = bindingIsCurrent() && state.compareAndSet(PENDING, ADMITTED)

        fun isCurrent(): Boolean = state.get() != SUPERSEDED && bindingIsCurrent()

        // Once admitted, geometry must install its matching viewport anchor before later work.
        fun supersede() {
            state.compareAndSet(PENDING, SUPERSEDED)
        }

        fun completeSafely(failure: Throwable?) {
            try {
                complete(failure)
            } catch (next: Throwable) {
                // A host callback cannot stop delivery of already accepted mutations.
                LOGGER.log(System.Logger.Level.WARNING, "Terminal view mutation completion failed", next)
            }
        }
    }

    private companion object {
        const val CAPACITY = 64
        const val PENDING = 0
        const val ADMITTED = 1
        const val SUPERSEDED = 2
        val LOGGER: System.Logger = System.getLogger(SessionMutationQueue::class.java.name)
    }
}
