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
package io.github.ketraterm.app.ui;

import io.github.ketraterm.ui.swing.api.SwingHostServices;
import io.github.ketraterm.ui.swing.api.SwingTerminal;
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider;
import kotlinx.coroutines.CoroutineDispatcher;

/** Reuses the view's JVM-visible test constructor without widening its Kotlin embedding API. */
final class SuggestionTestTerminal {
    private SuggestionTestTerminal() {}

    static SwingTerminal create(
            SwingSettingsProvider settings,
            SwingHostServices services,
            CoroutineDispatcher dispatcher) {
        return new SwingTerminal(
                settings, services, dispatcher, dispatcher, component -> null, dispatcher, dispatcher);
    }
}
