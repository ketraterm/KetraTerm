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

import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag

/**
 * KetraTerm's advertised terminal identity and capability contract.
 *
 * These values are shared vocabulary for launch environments and
 * terminal-to-host query responses. They are intentionally conservative:
 * advertise compatibility that contemporary shells and TUIs need, avoid stable
 * product/version fingerprints, and do not claim query families whose response
 * policy is not implemented.
 */
object TerminalCapabilityIdentity {
    /**
     * `$TERM` and XTGETTCAP `TN`/`name` identity exposed to terminal programs.
     */
    const val TERM_NAME: String = "xterm-256color"

    /**
     * `COLORTERM` value used by local process launchers to advertise truecolor.
     */
    const val COLOR_TERM_TRUECOLOR: String = "truecolor"

    /**
     * Primary DA terminal class. `1` is VT100.
     */
    const val PRIMARY_DA_TERMINAL_CLASS: Int = 1

    /**
     * Primary DA option for advanced video support.
     */
    const val PRIMARY_DA_ADVANCED_VIDEO: Int = 2

    /**
     * Secondary DA terminal id. Zero is a generic/versionless identity.
     */
    const val SECONDARY_DA_TERMINAL_ID: Int = 0

    /**
     * Secondary DA firmware/version field. Zero avoids product version leakage.
     */
    const val SECONDARY_DA_VERSION: Int = 0

    /**
     * Secondary DA keyboard/options field. Zero avoids overclaiming xterm options.
     */
    const val SECONDARY_DA_OPTIONS: Int = 0

    /**
     * DA3 is not answered because it can expose a stable unit id.
     */
    const val TERTIARY_DA_SUPPORTED: Boolean = false

    /**
     * XTGETTCAP color-count value for `Co` and `colors`.
     */
    const val TERMINFO_COLOR_COUNT: String = "256"

    /**
     * XTGETTCAP boolean TrueColor support for `RGB` and `Tc`.
     */
    const val TERMINFO_TRUECOLOR_SUPPORTED: Boolean = true

    /**
     * Kitty keyboard progressive flags currently understood by input encoding.
     */
    const val KITTY_KEYBOARD_SUPPORTED_FLAGS: Int = KittyKeyboardProgressiveFlag.SUPPORTED_MASK

    /**
     * `CSI ? u` Kitty keyboard capability queries return only the active,
     * supported progressive-enhancement flags through the terminal response
     * policy channel.
     */
    const val KITTY_KEYBOARD_QUERY_RESPONSE_SUPPORTED: Boolean = true
}
