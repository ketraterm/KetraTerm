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
package io.github.ketraterm.ui.swing.host

/**
 * Shared ranges for terminal host settings controls and persisted preference normalization.
 * These are UI preference limits, not core-buffer or renderer capability limits.
 * Values outside them can still be valid for direct library configuration.
 */
public object SwingTerminalSettingsBounds {
    /** columns min for host preferences. */
    public const val COLUMNS_MIN: Int = 10

    /** columns max for host preferences. */
    public const val COLUMNS_MAX: Int = 1000

    /** rows min for host preferences. */
    public const val ROWS_MIN: Int = 10

    /** rows max for host preferences. */
    public const val ROWS_MAX: Int = 500

    /** font size min for host preferences. */
    public const val FONT_SIZE_MIN: Int = 10

    /** font size max for host preferences. */
    public const val FONT_SIZE_MAX: Int = 56

    /** cursor blink min for host preferences. */
    public const val CURSOR_BLINK_MIN: Int = 0

    /** cursor blink max for host preferences. */
    public const val CURSOR_BLINK_MAX: Int = 10_000

    /** scrollback min for host preferences. */
    public const val SCROLLBACK_MIN: Int = 0

    /** scrollback max for host preferences. */
    public const val SCROLLBACK_MAX: Int = 1_000_000

    /** line height min for host preferences. */
    public const val LINE_HEIGHT_MIN: Float = 0.7f

    /** line height max for host preferences. */
    public const val LINE_HEIGHT_MAX: Float = 1.5f
}
