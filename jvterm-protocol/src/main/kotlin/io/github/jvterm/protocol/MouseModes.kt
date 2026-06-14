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
package io.github.jvterm.protocol

/** Mouse tracking selection toggled by DECSET private modes. */
enum class MouseTrackingMode {
    /** Mouse tracking is disabled. */
    OFF,

    /** X10 compatibility tracking: report button presses only. */
    X10,

    /** Normal tracking: report button presses and releases. */
    NORMAL,

    /** Button event tracking: report button presses, releases, and drag events. */
    BUTTON_EVENT,

    /** Any event tracking: report button presses, releases, drag events, and motion/hover events. */
    ANY_EVENT,
}

/** Mouse report encoding selected by xterm private modes. */
enum class MouseEncodingMode {
    /** Standard X11 byte-value encoding. */
    DEFAULT,

    /** UTF-8 coordinate encoding (legacy extension). */
    UTF8,

    /** Modern standard SGR mouse encoding. */
    SGR,

    /** urxvt-style decimal mouse encoding (legacy extension). */
    URXVT,

    /** SGR-style pixel-level coordinate mouse encoding. */
    SGR_PIXELS,
}
