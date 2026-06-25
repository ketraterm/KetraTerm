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
package io.github.ketraterm.host

/**
 * Host policy for terminal-triggered icon and window title metadata updates.
 *
 * OSC 0/1/2 and xterm title-stack restore operations can rename host UI
 * surfaces. That is convenient for a standalone local terminal, but product
 * hosts such as IDE tabs, workspace panes, and SSH sessions may want stricter
 * behavior to prevent confusing or spoofed labels. The host chooses [origin]
 * per session and this policy applies the matching permission before adapter or
 * core title metadata is changed.
 *
 * @property origin whether the terminal stream comes from a local or remote
 * session from the host product's perspective.
 * @property localPermission permission for local terminal streams.
 * @property remotePermission permission for remote terminal streams.
 * @property overflowPolicy handling for titles longer than [maxLength].
 * @property maxLength maximum retained title length in UTF-16 code units.
 */
data class TerminalTitlePolicy(
    val origin: TerminalTitleOrigin = TerminalTitleOrigin.LOCAL,
    val localPermission: TerminalTitlePermission = TerminalTitlePermission.ALLOW,
    val remotePermission: TerminalTitlePermission = TerminalTitlePermission.ALLOW,
    val overflowPolicy: TerminalTitleOverflowPolicy = TerminalTitleOverflowPolicy.CLAMP,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    init {
        require(maxLength >= 0) {
            "maxLength must be non-negative, got $maxLength"
        }
    }

    internal val permission: TerminalTitlePermission
        get() =
            when (origin) {
                TerminalTitleOrigin.LOCAL -> localPermission
                TerminalTitleOrigin.REMOTE -> remotePermission
            }

    /** Shared default maximum retained title length in UTF-16 code units. */
    companion object {
        const val DEFAULT_MAX_LENGTH: Int = 4096
    }
}

/**
 * Terminal stream origin used when evaluating title update policy.
 */
enum class TerminalTitleOrigin {
    /** A local process attached through a local PTY or equivalent connector. */
    LOCAL,

    /** A remote process reached through SSH or another remote transport. */
    REMOTE,
}

/**
 * Permission for terminal-triggered title metadata updates.
 */
enum class TerminalTitlePermission {
    /** Ignore terminal title updates and title-stack restore effects. */
    DENY,

    /** Accept terminal title updates subject to [TerminalTitlePolicy] bounds. */
    ALLOW,
}

/**
 * Handling for terminal titles that exceed the configured maximum length.
 */
enum class TerminalTitleOverflowPolicy {
    /**
     * Ignore the title update when the incoming title is longer than the
     * configured maximum.
     */
    REJECT,

    /**
     * Retain the title prefix up to the configured maximum and discard the
     * remainder.
     */
    CLAMP,
}
