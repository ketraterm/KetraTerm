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
package io.github.ketraterm.ui.swing

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.settings.SwingSettings

/** Tracks host values for one binding, independently of application-controlled state. */
internal class SessionSettingsState {
    // The EDT owns this count. Applied values belong to the ordered worker while it is nonzero.
    var pendingRequests = 0
    private var ambiguousAsWide: Boolean? = null
    private var palette: TerminalColorPalette? = null
    private var cursorShape: TerminalRenderCursorShape? = null

    fun matches(settings: SwingSettings): Boolean =
        ambiguousAsWide == settings.treatAmbiguousAsWide && palette == settings.palette && cursorShape == settings.cursorShape

    fun apply(
        session: TerminalSession,
        settings: SwingSettings,
    ) {
        if (ambiguousAsWide != settings.treatAmbiguousAsWide) {
            update(
                apply = { session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide) },
                committed = { session.modeSnapshot.treatAmbiguousAsWide == settings.treatAmbiguousAsWide },
                remember = { ambiguousAsWide = settings.treatAmbiguousAsWide },
            )
        }
        if (palette != settings.palette) {
            update(
                apply = { session.setThemePalette(settings.palette) },
                committed = { session.palette == settings.palette },
                remember = { palette = settings.palette },
            )
        }
        if (cursorShape != settings.cursorShape) {
            update(
                apply = { session.setCursorShape(settings.cursorShape) },
                committed = {
                    var matches = false
                    session.readRenderFrame { matches = it.cursor.shape == settings.cursorShape }
                    matches
                },
                remember = { cursorShape = settings.cursorShape },
            )
        }
    }

    private inline fun update(
        apply: () -> Unit,
        committed: () -> Boolean,
        remember: () -> Unit,
    ) {
        try {
            apply()
        } catch (failure: Exception) {
            try {
                if (committed()) remember()
            } catch (observationFailure: Exception) {
                preserveSwingFailure(failure, observationFailure)
            }
            throw failure
        }
        remember()
    }
}
