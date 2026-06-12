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
package com.gagik.terminal.protocol.keyboard

/**
 * Xterm modifyOtherKeys mode values stored in core's packed input-mode word.
 */
object ModifyOtherKeysMode {
    /** Do not use modifyOtherKeys encoding. */
    const val DISABLED: Int = 0

    /** Encode ordinary modified keys whose legacy representation is ambiguous or missing. */
    const val MODE_1: Int = 1

    /** Encode ordinary modified keys plus xterm's Tab/Enter control-equivalent exceptions. */
    const val MODE_2: Int = 2

    /** Encode ordinary keys even when no modifiers are active. */
    const val MODE_3: Int = 3
}
