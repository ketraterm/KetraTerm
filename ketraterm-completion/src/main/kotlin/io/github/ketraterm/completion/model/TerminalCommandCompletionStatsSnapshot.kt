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
package io.github.ketraterm.completion.model

/**
 * Complete snapshot of exact command, structural shape, and feedback statistics.
 *
 * Hosts persist this model as compact suggestion-learning metadata. The
 * snapshot intentionally contains aggregate counters only; raw repeated history
 * rows and raw terminal output are outside this contract.
 *
 * @property commandStats exact command-line rows used for concrete suggestions.
 * @property shapeStats privacy-preserving command-shape rows used for ranking
 * analytics and shape-aware suggestion ranking.
 * @property feedbackStats source-specific feedback rows used for provider-aware
 * ranking.
 */
data class TerminalCommandCompletionStatsSnapshot(
    val commandStats: List<TerminalCommandCompletionStats> = emptyList(),
    val shapeStats: List<TerminalCommandShapeStats> = emptyList(),
    val feedbackStats: List<TerminalCompletionFeedbackStats> = emptyList(),
)
