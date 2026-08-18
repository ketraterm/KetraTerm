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
package io.github.ketraterm.completion.api

/**
 * Presentation role used when multiple completion sources support the same
 * outcome with an identical shell edit: equal replacement text, start offset,
 * and end offset.
 *
 * This role affects only which complete candidate supplies public presentation
 * metadata. Every source continues to contribute normally to evidence fusion
 * and final ranking.
 */
enum class TerminalCompletionSourcePresentationRole {
    /** Supplies the preferred presentation among equally applicable sources for an identical edit. */
    PRIMARY,

    /** Supplies presentation only when no equally applicable primary source returns the same edit. */
    FALLBACK,
}
