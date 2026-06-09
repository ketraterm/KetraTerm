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
package com.gagik.terminal.standalone.config

import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalTheme

/**
 * Mutable standalone application settings model.
 *
 * The reusable Swing terminal still consumes immutable settings snapshots. This
 * host model owns the mutable app preferences and creates fresh snapshots when
 * the user changes a menu setting.
 */
internal class StandaloneTerminalSettings {
    var theme: TerminalTheme = TerminalTheme.CAMPBELL
    var treatAmbiguousAsWide: Boolean = false

    fun current(): TerminalSwingSettings =
        TerminalSwingSettings(
            columns = INITIAL_COLUMNS,
            rows = INITIAL_ROWS,
            palette = theme.createPalette(),
            treatAmbiguousAsWide = treatAmbiguousAsWide,
        )

    private companion object {
        private const val INITIAL_COLUMNS = 100
        private const val INITIAL_ROWS = 30
    }
}
