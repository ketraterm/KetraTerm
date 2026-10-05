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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Host-owned authoritative command-line projection for conditional editing.
 * Every assignment advances a revision, including equal values and transitions
 * through null. Publish null while the host cannot trust its editing state,
 * including host-managed pending input. Publishing a snapshot does not acknowledge
 * remote processing of session writes; that remains the host shell model's concern.
 *
 * Assignments serialize with session edit checks and queue reservation. Publish
 * only after the corresponding terminal output has been delivered; never deliver
 * terminal output from inside a projection read. This object owns no coroutine
 * scope and remains host-owned after session closure.
 */
public class TerminalShellCommandLineState
    @JvmOverloads
    public constructor(
        initialValue: TerminalShellCommandLineSnapshot? = null,
    ) {
        private val lock = Any()
        private var snapshot = initialValue
        private var revision = 0L
        private val notifications = MutableStateFlow(0L)

        /**
         * Current snapshot, or null when editing is unavailable. Each assignment
         * is a new revision. Throws IllegalStateException without changing the
         * snapshot if the revision counter is exhausted.
         */
        public var value: TerminalShellCommandLineSnapshot?
            get() = synchronized(lock) { snapshot }
            set(value) {
                val next =
                    synchronized(lock) {
                        check(revision != Long.MAX_VALUE) { "Command-line revisions exhausted" }
                        snapshot = value
                        ++revision
                    }
                // Resume observers outside the producer guard; concurrent publications cannot regress it.
                notifications.update { maxOf(it, next) }
            }

        internal val changes: Flow<Long> get() = notifications

        internal fun <T> withCommandLine(action: (Long, TerminalShellCommandLineSnapshot?) -> T): T =
            synchronized(lock) { action(revision, snapshot) }
    }
