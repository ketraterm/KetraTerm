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
package io.github.ketraterm.parser.unicode

import io.github.ketraterm.parser.runtime.ParserState

internal object GraphemeSegmenter {
    @JvmStatic
    fun continuesCurrentCluster(
        state: ParserState,
        currentClass: Int,
        codepoint: Int,
    ): Boolean {
        val previousClass = state.prevGraphemeBreakClass

        if (previousClass == UnicodeClass.GRAPHEME_CR && currentClass == UnicodeClass.GRAPHEME_LF) {
            return true
        }

        if (isBreakForcingClass(previousClass) || isBreakForcingClass(currentClass)) {
            return false
        }

        return previousClass == UnicodeClass.GRAPHEME_PREPEND ||
            currentClass == UnicodeClass.GRAPHEME_EXTEND ||
            currentClass == UnicodeClass.GRAPHEME_ZWJ ||
            currentClass == UnicodeClass.GRAPHEME_SPACING_MARK ||
            isHangulContinuation(previousClass, currentClass) ||
            isRegionalIndicatorContinuation(state, currentClass) ||
            isExtendedPictographicZwjContinuation(state, codepoint, currentClass)
    }

    @JvmStatic
    fun updateContext(
        state: ParserState,
        codepoint: Int,
        currentClass: Int,
    ) {
        state.prevWasZwj = currentClass == UnicodeClass.GRAPHEME_ZWJ

        if (currentClass == UnicodeClass.GRAPHEME_REGIONAL_INDICATOR) {
            state.regionalIndicatorParity = state.regionalIndicatorParity xor 1
        } else {
            state.regionalIndicatorParity = 0
        }

        if (currentClass == UnicodeClass.GRAPHEME_ZWJ) {
            state.zwjBeforeExtendedPictographic = state.lastNonExtendWasExtendedPictographic
        } else if (currentClass != UnicodeClass.GRAPHEME_EXTEND) {
            state.lastNonExtendWasExtendedPictographic = UnicodeClass.isExtendedPictographic(codepoint)
        }

        state.prevGraphemeBreakClass = currentClass
    }

    private fun isBreakForcingClass(graphemeClass: Int): Boolean =
        graphemeClass == UnicodeClass.GRAPHEME_CR ||
            graphemeClass == UnicodeClass.GRAPHEME_LF ||
            graphemeClass == UnicodeClass.GRAPHEME_CONTROL

    private fun isHangulContinuation(
        previousClass: Int,
        currentClass: Int,
    ): Boolean =
        when (previousClass) {
            UnicodeClass.GRAPHEME_L ->
                currentClass == UnicodeClass.GRAPHEME_L ||
                    currentClass == UnicodeClass.GRAPHEME_V ||
                    currentClass == UnicodeClass.GRAPHEME_LV ||
                    currentClass == UnicodeClass.GRAPHEME_LVT

            UnicodeClass.GRAPHEME_LV,
            UnicodeClass.GRAPHEME_V,
            ->
                currentClass == UnicodeClass.GRAPHEME_V ||
                    currentClass == UnicodeClass.GRAPHEME_T

            UnicodeClass.GRAPHEME_LVT,
            UnicodeClass.GRAPHEME_T,
            -> currentClass == UnicodeClass.GRAPHEME_T

            else -> false
        }

    private fun isRegionalIndicatorContinuation(
        state: ParserState,
        currentClass: Int,
    ): Boolean =
        currentClass == UnicodeClass.GRAPHEME_REGIONAL_INDICATOR &&
            state.regionalIndicatorParity == 1

    private fun isExtendedPictographicZwjContinuation(
        state: ParserState,
        codepoint: Int,
        currentClass: Int,
    ): Boolean =
        currentClass == UnicodeClass.GRAPHEME_OTHER &&
            state.prevWasZwj &&
            state.zwjBeforeExtendedPictographic &&
            UnicodeClass.isExtendedPictographic(codepoint)
}
