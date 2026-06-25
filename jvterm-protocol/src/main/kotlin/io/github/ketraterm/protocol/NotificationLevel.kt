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
package io.github.ketraterm.protocol

import java.util.*

/**
 * Severity level of a desktop notification.
 */
enum class NotificationLevel {
    /** Standard information notification. */
    INFO,

    /** Warning notification. */
    WARNING,

    /** Critical/error notification. */
    ERROR,

    /** Custom notification with no OS icons (uses application icon). */
    NONE,
    ;

    companion object {
        /**
         * Resolves the notification level from a string.
         * Falls back to null if unresolved or blank.
         *
         * @param value raw string to resolve.
         * @return resolved [NotificationLevel], or null if unresolved or blank.
         */
        @JvmStatic
        fun parseOrNull(value: String): NotificationLevel? {
            val cleaned = value.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull { it.name == cleaned }
        }
    }
}
