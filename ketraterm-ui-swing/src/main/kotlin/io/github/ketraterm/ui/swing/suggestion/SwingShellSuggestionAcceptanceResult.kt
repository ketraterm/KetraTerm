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

/** Outcome at the configured editing boundary; acceptance never acknowledges transport or shell execution. */
public enum class SwingShellSuggestionAcceptanceResult {
    /** The editing authority admitted or applied the complete edit. */
    ACCEPTED,

    /** The original request, publication, or editing capability is obsolete. */
    STALE_CONTEXT,

    /** The replacement cannot be applied to the original command-line context. */
    INVALID_EDIT,

    /** The editing authority is not running, has closed, or cannot admit more input. */
    UNAVAILABLE,

    /** The configured target does not support conditional editing. */
    UNSUPPORTED,

    /** The custom editing authority declined the operation. */
    REJECTED,
}
