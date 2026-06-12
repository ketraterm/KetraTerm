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

/** Mouse tracking selection toggled by DECSET private modes. */
enum class MouseTrackingMode {
    OFF,
    X10,
    NORMAL,
    BUTTON_EVENT,
    ANY_EVENT,
}

/** Mouse report encoding selected by xterm private modes. */
enum class MouseEncodingMode {
    DEFAULT,
    UTF8,
    SGR,
    URXVT,
}
