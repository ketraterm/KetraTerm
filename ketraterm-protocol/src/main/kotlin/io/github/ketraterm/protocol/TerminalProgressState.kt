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

/** Application-reported OSC 9;4 progress presentation state, independent of command completion. */
public enum class TerminalProgressState(
    /** Decimal state identifier carried by the protocol; do not use the enum ordinal. */
    public val wireValue: Int,
) {
    /** Remove the progress indicator. The percentage is ignored. */
    HIDDEN(0),

    /** Determinate progress with the application's percentage, including zero. */
    NORMAL(1),

    /** Determinate progress requiring error presentation. */
    ERROR(2),

    /** Work is in progress without a known percentage. The percentage is ignored. */
    INDETERMINATE(3),

    /** Determinate progress requiring warning or paused presentation. */
    WARNING(4),
}
