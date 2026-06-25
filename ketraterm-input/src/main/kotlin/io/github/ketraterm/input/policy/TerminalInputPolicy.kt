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
package io.github.ketraterm.input.policy

/**
 * Runtime policy for keyboard encodings that do not have one unambiguous
 * terminal byte representation.
 *
 * The production encoder suppresses unsupported combinations by default rather
 * than throwing, because UI toolkits may report platform-specific key states.
 *
 * @property backspacePolicy byte sent for the Backspace key.
 * @property metaKeyPolicy handling for Meta-only printable and legacy key
 * encodings.
 * @property unsupportedModifiedKeyPolicy handling for valid key events whose
 * modifier combination has no supported encoding in the active protocol set.
 * @property altSendsEscapePrefix when true, Alt prefixes applicable legacy
 * encodings with ESC.
 * @property enterNewLineModePolicy handling for unmodified Return/Enter when
 * ANSI Line Feed/New Line mode is active.
 * @property mouseCoordinateLimitPolicy handling for legacy mouse coordinates
 * outside the bounded `ESC [ M` byte range.
 * @property pasteSanitizationPolicy handling for pasted text before optional
 * bracketed-paste wrapping.
 */
data class TerminalInputPolicy(
    val backspacePolicy: BackspacePolicy = BackspacePolicy.DELETE,
    val metaKeyPolicy: MetaKeyPolicy = MetaKeyPolicy.ESC_PREFIX,
    val unsupportedModifiedKeyPolicy: UnsupportedModifiedKeyPolicy =
        UnsupportedModifiedKeyPolicy.SUPPRESS,
    val altSendsEscapePrefix: Boolean = true,
    val enterNewLineModePolicy: EnterNewLineModePolicy = EnterNewLineModePolicy.SEND_CR_LF,
    val mouseCoordinateLimitPolicy: MouseCoordinateLimitPolicy =
        MouseCoordinateLimitPolicy.SUPPRESS_OUT_OF_RANGE,
    val pasteSanitizationPolicy: PasteSanitizationPolicy = PasteSanitizationPolicy.RAW,
)

/**
 * Backspace byte selection.
 */
enum class BackspacePolicy {
    /** Send DEL, 0x7F. */
    DELETE,

    /** Send BS, 0x08. */
    BACKSPACE,
}

/**
 * Meta key handling for legacy printable/control encodings.
 *
 * Special keys with xterm CSI modifier encodings may still encode Meta as
 * modifier parameter 9.
 */
enum class MetaKeyPolicy {
    /** Prefix the encoded key with ESC. */
    ESC_PREFIX,

    /** Ignore Meta and encode the event as if Meta were not present. */
    IGNORE_META,

    /** Suppress the event when Meta is the governing modifier. */
    SUPPRESS_EVENT,
}

/**
 * Handling for valid modified key events without a supported encoding.
 */
enum class UnsupportedModifiedKeyPolicy {
    /** Emit no bytes. */
    SUPPRESS,

    /** Drop the unsupported modifier and emit the unmodified key encoding. */
    EMIT_UNMODIFIED,
}

/**
 * Policy for legacy mouse coordinates beyond the byte-safe range.
 */
enum class MouseCoordinateLimitPolicy {
    /** Suppress events whose one-based coordinate is greater than 223. */
    SUPPRESS_OUT_OF_RANGE,

    /** Clamp events whose one-based coordinate is greater than 223 to 223. */
    CLAMP_TO_MAX,
}

/**
 * Policy for Return/Enter while ANSI Line Feed/New Line mode is active.
 */
enum class EnterNewLineModePolicy {
    /** Send DEC-compatible CR LF when LNM is active. */
    SEND_CR_LF,

    /** Always send CR, leaving PTY/host line discipline to supply newline behavior. */
    SEND_CR,
}

/**
 * Policy for paste payload transformation before terminal-host emission.
 */
enum class PasteSanitizationPolicy {
    /** Preserve paste payload exactly as provided by the host/UI layer. */
    RAW,

    /** Drop C0 controls except TAB, CR, and LF. */
    STRIP_C0_EXCEPT_TAB_CR_LF,

    /** Normalize CRLF and lone CR line endings to LF. */
    NORMALIZE_LINE_ENDINGS,
}
