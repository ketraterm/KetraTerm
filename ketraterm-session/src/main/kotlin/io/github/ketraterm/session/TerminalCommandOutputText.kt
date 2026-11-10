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
package io.github.ketraterm.session

/**
 * Detached tail of a completed command's retained, rendered output.
 *
 * This is logical terminal text, not a process transcript or separate stdout/stderr streams.
 * Soft wraps are joined, hard row breaks become newlines, and terminal padding is omitted.
 * Text can be empty with [truncated] set when even the final grapheme exceeds the character limit.
 *
 * @property text retained tail, bounded in UTF-16 code units without splitting stored graphemes.
 * @property truncated caller limits omitted retained output, independently of history loss.
 * @property historyIncomplete at least one recorded range boundary is no longer retained;
 *   the returned text must not be treated as the command's complete output.
 */
public data class TerminalCommandOutputText(
    val text: String,
    val truncated: Boolean,
    val historyIncomplete: Boolean,
)
