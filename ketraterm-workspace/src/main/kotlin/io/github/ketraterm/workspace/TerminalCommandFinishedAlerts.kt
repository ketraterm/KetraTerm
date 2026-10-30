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
package io.github.ketraterm.workspace

import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata

/** Shared product policy; hosts own preferences, focus queries, dispatch, and notification delivery. */
object TerminalCommandFinishedAlerts {
    /** Smallest configurable command runtime, in seconds. */
    const val MINIMUM_DURATION_SECONDS: Int = 1

    /** Largest configurable command runtime, in seconds (one day). */
    const val MAXIMUM_DURATION_SECONDS: Int = 86_400

    /** Initial threshold for the opt-in product preference, in seconds. */
    const val DEFAULT_MINIMUM_DURATION_SECONDS: Int = 10

    /**
     * Returns whether this explicit completion qualifies for a product alert.
     *
     * This synchronous function reads no mutable host state and performs no I/O. Hosts capture
     * [durationMillis] from the originating shell model during the completion callback, then
     * recheck current preferences, permission, ownership, lifecycle, and focus at delivery.
     *
     * @param durationMillis monotonic elapsed runtime, or null when unavailable; epoch timestamps
     * are deliberately ignored. Negative durations never qualify.
     * @param enabled current opt-in preference.
     * @param minimumDurationSeconds inclusive runtime threshold, within the constants above.
     * @param focused whether the originating session's pane contains the current focus owner.
     * @param notificationsAllowed current host notification permission and product master setting.
     * @throws IllegalArgumentException when the threshold is outside the supported range.
     */
    fun shouldNotify(
        metadata: TerminalShellIntegrationCommandMetadata,
        durationMillis: Long?,
        enabled: Boolean,
        minimumDurationSeconds: Int,
        focused: Boolean,
        notificationsAllowed: Boolean,
    ): Boolean {
        require(minimumDurationSeconds in MINIMUM_DURATION_SECONDS..MAXIMUM_DURATION_SECONDS) {
            "minimumDurationSeconds must be in $MINIMUM_DURATION_SECONDS..$MAXIMUM_DURATION_SECONDS"
        }
        if (!enabled || focused || !notificationsAllowed || durationMillis == null) return false
        if (durationMillis < minimumDurationSeconds.toLong() * 1_000L) return false
        return when (metadata.lifecycle) {
            TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            TerminalShellIntegrationCommandLifecycle.FAILED,
            TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN,
            -> true
            else -> false
        }
    }
}
