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

    /** Primary DA terminal class. `64` identifies VT420-level DEC controls. */
    const val PRIMARY_DA_TERMINAL_CLASS: Int = 64

    /** Primary DA allowlisted option: 132-column mode. */
    const val PRIMARY_DA_132_COLUMNS: Int = 1

    /** Primary DA allowlisted option: selective erase. */
    const val PRIMARY_DA_SELECTIVE_ERASE: Int = 6

    /** Primary DA allowlisted option: ANSI color. */
    const val PRIMARY_DA_ANSI_COLOR: Int = 22

    /** Primary DA allowlisted option: rectangular editing. */
    const val PRIMARY_DA_RECTANGULAR_EDITING: Int = 28

    /**
     * Secondary DA terminal id. `41` is the VT420 equipment class.
     */
    const val SECONDARY_DA_TERMINAL_ID: Int = 41

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
     * Conservative Kitty keyboard progressive flags for the default host
     * profile. Per-session host integration may advertise a different subset
     * of encoder-supported flags when it can provide the required metadata.
     */
    const val KITTY_KEYBOARD_SUPPORTED_FLAGS: Int = KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK

    /**
     * `CSI ? u` Kitty keyboard capability queries return only the active,
     * supported progressive-enhancement flags through the terminal response
     * policy channel.
     */
    const val KITTY_KEYBOARD_QUERY_RESPONSE_SUPPORTED: Boolean = true
}
