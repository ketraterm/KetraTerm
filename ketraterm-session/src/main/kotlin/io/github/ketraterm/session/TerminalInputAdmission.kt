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

/**
 * Result of synchronous session input admission, never a write receipt.
 * An accepted operation can still be interrupted or discarded by closure or
 * transport failure. Observe [TerminalSession.state] for termination and its
 * original failure; response-dependent callers must stop waiting on closure.
 */
public enum class TerminalInputAdmission {
    /** Accepted in session order, including empty or mode/policy-suppressed input. */
    ACCEPTED,

    /** Connector startup has not completed; nothing was admitted. */
    NOT_RUNNING,

    /** Shutdown has begun; nothing was admitted. */
    CLOSED,

    /** A queue or operation budget was exceeded; nothing was admitted and the session fails closed. */
    CAPACITY_EXCEEDED,

    /** The issuing session, shell revision, input, output or geometry no longer matches. */
    STALE_CONTEXT,

    /** The selected shell producer cannot serialize conditional editing. */
    UNSUPPORTED_CONTEXT,

    /** The owner cancelled the captured editing context before the final check. */
    CANCELLED,
}
