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

import io.github.ketraterm.host.*
import io.github.ketraterm.input.TerminalClipboardReply
import io.github.ketraterm.protocol.host.TerminalHostOutput
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One read lifetime per session, including cancelled providers that have not
 * returned. All state transitions and output commitment use the outbound lock.
 * Clipboard access and payload preparation run outside that lock.
 */
internal class ClipboardReadHandler(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val lock: Any,
    private val writer: OutboundWriter,
    private val output: () -> TerminalHostOutput,
    private val reader: TerminalClipboardReader?,
    private val policy: () -> HostPolicy,
    private val isClosed: () -> Boolean,
    private val audit: (TerminalClipboardReadAuditEvent) -> Unit,
    private val timeSource: TimeSource,
) {
    private var active: Read? = null

    fun request(request: TerminalClipboardReadRequest) {
        synchronized(lock) {
            if (isClosed()) return
            if (active != null) {
                audit(TerminalClipboardReadAuditEvent(request.selection, TerminalClipboardReadOutcome.BUSY))
                return
            }
            val read = Read(request, timeSource.markNow())
            active = read
            read.timeout =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(DEADLINE)
                    expire(read)
                }
            if (!eligible(read)) {
                retire(read, TerminalClipboardReadOutcome.CANCELLED)
                return
            }
            if (request.permission == TerminalClipboardPermission.DENY || reader == null) {
                val outcome =
                    if (request.permission == TerminalClipboardPermission.DENY) {
                        TerminalClipboardReadOutcome.DENIED
                    } else {
                        TerminalClipboardReadOutcome.UNAVAILABLE
                    }
                enqueue(read, emptyReply(read), outcome)
                return
            }
            read.providerFinished = false
            val job =
                scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                    execute(read)
                }
            read.provider = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    read.providerFinished = true
                    if (!read.retired && !read.queued) retire(read, TerminalClipboardReadOutcome.CANCELLED)
                    releaseSlot(read)
                }
            }
            job.start()
        }
    }

    /** Called under the outbound lock after publishing the new host policy. */
    fun policyChanged() {
        synchronized(lock) {
            val read = active ?: return
            if (!read.committed && !eligible(read)) retire(read, TerminalClipboardReadOutcome.CANCELLED)
        }
    }

    fun close() {
        synchronized(lock) {
            active?.let { retire(it, TerminalClipboardReadOutcome.CANCELLED) }
        }
    }

    private suspend fun execute(read: Read) {
        val current = currentCoroutineContext()
        current.ensureActive()
        synchronized(lock) {
            if (read.retired || !eligible(read)) return
            if (read.started.elapsedNow() >= DEADLINE) {
                expire(read)
                return
            }
        }
        var prepared: TerminalClipboardReply? = null
        var outcome = TerminalClipboardReadOutcome.FAILED
        try {
            when (val result = reader?.read(read.request) ?: TerminalClipboardReadResult.Unavailable) {
                is TerminalClipboardReadResult.Text -> {
                    current.ensureActive()
                    prepared =
                        TerminalClipboardReply.prepare(
                            read.request.selection,
                            result.text,
                            read.request.maxDecodedBytes,
                            OutboundWriter.MAX_REPLY_BYTES,
                        )
                    outcome = if (prepared == null) TerminalClipboardReadOutcome.INVALID_DATA else TerminalClipboardReadOutcome.SENT
                }
                TerminalClipboardReadResult.Denied -> outcome = TerminalClipboardReadOutcome.DENIED
                TerminalClipboardReadResult.Unavailable -> outcome = TerminalClipboardReadOutcome.UNAVAILABLE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Provider boundary: audit the outcome, never its possibly sensitive message.
            outcome = TerminalClipboardReadOutcome.FAILED
        }
        try {
            current.ensureActive()
            synchronized(lock) {
                if (read.retired || !eligible(read)) return
                if (read.started.elapsedNow() >= DEADLINE) {
                    expire(read)
                    return
                }
                enqueue(read, prepared ?: emptyReply(read), outcome)
                prepared = null // Ownership has passed to the queued request.
            }
        } finally {
            prepared?.close()
        }
    }

    private fun enqueue(
        read: Read,
        reply: TerminalClipboardReply,
        outcome: TerminalClipboardReadOutcome,
        onlyIfIdle: Boolean = false,
    ) {
        read.reply = reply
        read.outcome = outcome
        read.queued = true
        val accepted =
            writer.submitReply(
                reply.byteCount,
                onlyIfIdle = onlyIfIdle,
                write = { write(read) },
                release = {
                    read.queued = false
                    retire(read, if (isClosed() || !read.committed) TerminalClipboardReadOutcome.CANCELLED else read.outcome)
                },
            )
        if (!accepted) {
            read.queued = false
            retire(read, if (isClosed()) TerminalClipboardReadOutcome.CANCELLED else outcome)
        }
    }

    private fun write(read: Read) {
        val reply =
            synchronized(lock) {
                if (read.retired) return
                if (isClosed() || !eligible(read)) {
                    retire(read, TerminalClipboardReadOutcome.CANCELLED)
                    return
                }
                val commitDeadline = if (read.outcome == TerminalClipboardReadOutcome.TIMED_OUT) TIMEOUT_REPLY_DEADLINE else DEADLINE
                if (read.started.elapsedNow() >= commitDeadline) {
                    retire(read, TerminalClipboardReadOutcome.TIMED_OUT)
                    return
                }
                // Permission changes after this point cannot retract a started frame.
                read.committed = true
                read.timeout?.cancel()
                read.reply ?: return
            }
        try {
            reply.writeTo(output())
        } catch (failure: Exception) {
            synchronized(lock) {
                read.outcome = if (isClosed()) TerminalClipboardReadOutcome.CANCELLED else TerminalClipboardReadOutcome.FAILED
            }
            throw failure
        }
    }

    private fun expire(read: Read) {
        synchronized(lock) {
            if (read.retired || read.committed) return
            if (!read.queued && read.started.elapsedNow() < TIMEOUT_REPLY_DEADLINE && eligible(read) && !isClosed()) {
                enqueue(read, emptyReply(read), TerminalClipboardReadOutcome.TIMED_OUT, onlyIfIdle = true)
            } else {
                retire(read, TerminalClipboardReadOutcome.TIMED_OUT)
            }
            read.provider?.cancel()
        }
    }

    private fun eligible(read: Read): Boolean {
        val current = policy()
        if (current.terminalResponsePolicy != HostControlPolicy.ALLOW) return false
        val clipboard = current.clipboardPolicy
        val permission = read.request.permission
        return clipboard.maxDecodedBytes >= read.request.maxDecodedBytes &&
            (
                permission == TerminalClipboardPermission.DENY ||
                    clipboard.readPermission == permission ||
                    (permission == TerminalClipboardPermission.PROMPT && clipboard.readPermission == TerminalClipboardPermission.ALLOW)
            )
    }

    private fun retire(
        read: Read,
        outcome: TerminalClipboardReadOutcome,
    ) {
        if (!read.retired) {
            read.retired = true
            read.outcome = outcome
            read.timeout?.cancel()
            read.provider?.cancel()
            // A committed writer owns this buffer until its final release callback.
            if (!read.committed || !read.queued) {
                read.reply?.close()
                read.reply = null
            }
            audit(TerminalClipboardReadAuditEvent(read.request.selection, outcome))
        }
        if (!read.queued) {
            read.reply?.close()
            read.reply = null
        }
        releaseSlot(read)
    }

    private fun releaseSlot(read: Read) {
        if (active === read && read.retired && read.providerFinished && !read.queued) active = null
    }

    private fun emptyReply(read: Read): TerminalClipboardReply =
        checkNotNull(TerminalClipboardReply.prepare(read.request.selection, "", 0, OutboundWriter.MAX_REPLY_BYTES))

    private class Read(
        val request: TerminalClipboardReadRequest,
        val started: TimeMark,
    ) {
        var provider: Job? = null
        var timeout: Job? = null
        var providerFinished = true
        var reply: TerminalClipboardReply? = null
        var outcome = TerminalClipboardReadOutcome.CANCELLED
        var queued = false
        var committed = false
        var retired = false
    }

    private companion object {
        val DEADLINE = 8.seconds

        // Allow dispatch latency without letting a delayed timer or writer restart the window.
        val TIMEOUT_REPLY_DEADLINE = DEADLINE + 100.milliseconds
    }
}
