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
package com.gagik.terminal.protocol

/**
 * Xterm formatOtherKeys values stored in core's packed input-mode word.
 */
object FormatOtherKeysMode {
    /** Use the original xterm `CSI 27 ; modifier ; codepoint ~` format. */
    const val DEFAULT: Int = 0

    /** Use xterm's compact `CSI codepoint ; modifier u` format. */
    const val CSI_U: Int = 1
}
