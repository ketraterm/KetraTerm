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
package com.gagik.terminal.protocol.mouse

/**
 * Normalized mouse tracking modes exposed to input encoders.
 *
 * Values match the packed ordinals returned by core's input-state decoder.
 */
object MouseTrackingMode {
    /** Mouse reporting disabled. */
    const val NONE: Int = 0

    /** X10 tracking: press events only. */
    const val X10: Int = 1

    /** Normal tracking: press, release, and wheel events. */
    const val NORMAL: Int = 2

    /** Button-event tracking: normal tracking plus button drag motion. */
    const val BUTTON_EVENT: Int = 3

    /** Any-event tracking: button and no-button motion events. */
    const val ANY_EVENT: Int = 4
}

/**
 * Normalized mouse encoding modes exposed to input encoders.
 *
 * Values match the packed ordinals returned by core's input-state decoder.
 */
object MouseEncodingMode {
    /** Bounded legacy `ESC [ M` mouse encoding. */
    const val DEFAULT: Int = 0

    /** UTF-8 mouse encoding, currently policy-gated in input. */
    const val UTF8: Int = 1

    /** SGR decimal mouse encoding. */
    const val SGR: Int = 2

    /** URXVT mouse encoding, currently policy-gated in input. */
    const val URXVT: Int = 3
}
