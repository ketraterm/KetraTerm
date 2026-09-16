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

/** Session-local outcome of an explicitly configured startup command. */
enum class TerminalStartupCommandStatus {
    /** Awaiting a complete primary-screen prompt; no timer submits the command. */
    WAITING,

    /** Command and Enter were written; this does not imply command execution succeeded. */
    SUBMITTED,

    /** User input took precedence before submission. The command will not be retried. */
    CANCELLED_BY_INPUT,

    /** The session closed before submission. */
    CLOSED,

    /** A write failed. The command will not be retried because it may have been partially written. */
    FAILED,
}
