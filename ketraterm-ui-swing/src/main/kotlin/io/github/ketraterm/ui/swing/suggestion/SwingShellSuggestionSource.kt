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
package io.github.ketraterm.ui.swing.suggestion

import kotlinx.coroutines.flow.Flow

/**
 * Provider resources captured together for one request, before collection starts.
 *
 * @property suggestions cold progressive snapshots, collected outside the EDT by the terminal binding.
 * @property feedbackHandler observer for this source's original request, independent of later provider replacement.
 */
public class SwingShellSuggestionSource(
    public val suggestions: Flow<List<SwingShellSuggestion>>,
    public val feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
)
