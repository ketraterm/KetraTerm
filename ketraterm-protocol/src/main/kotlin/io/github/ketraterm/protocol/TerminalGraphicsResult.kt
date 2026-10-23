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
package io.github.ketraterm.protocol

/**
 * Graphics validation/admission outcomes. [reply] is fixed ASCII text without
 * input-derived content; the host still applies reply eligibility and permission.
 *
 * @property reply payload after the reply's semicolon, without framing or identifiers.
 */
public enum class TerminalGraphicsResult(
    public val reply: String,
) {
    /** No failure at the producing stage; final core success means applied operation or validated query. */
    OK("OK"),

    /** Invalid control values or image data. */
    INVALID("EINVAL:invalid graphics data"),

    /** A recognized operation, medium or format is unavailable. */
    UNSUPPORTED("ENOTSUP:unsupported graphics operation"),

    /** The referenced image does not exist in the active screen. */
    MISSING("ENOENT:image not found"),

    /** One transfer or image exceeds an individual size bound. */
    TOO_LARGE("EFBIG:graphics limit exceeded"),

    /** Admission cannot fit the retained resource or placement budget. */
    NO_SPACE("ENOSPC:graphics storage exhausted"),
}
