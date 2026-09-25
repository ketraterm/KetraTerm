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

/** Wire values of the DECRPM mode-status parameter. */
object TerminalModeStatus {
    /** The queried mode has no supported status report. */
    const val UNRECOGNIZED: Int = 0

    /** The mode is currently set. */
    const val SET: Int = 1

    /** The mode is currently reset. */
    const val RESET: Int = 2

    /** The mode is fixed in the set state. */
    const val PERMANENTLY_SET: Int = 3

    /** The mode is fixed in the reset state. */
    const val PERMANENTLY_RESET: Int = 4
}
