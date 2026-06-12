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
package com.gagik.parser.ansi.sgr

import com.gagik.parser.runtime.ParserState
import com.gagik.parser.spi.TerminalCommandSink

internal object SgrDispatcher {
    fun dispatch(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        if (state.paramCount == 0) {
            sink.resetAttributes()
            return
        }

        var i = 0
        while (i < state.paramCount) {
            val param = state.params[i]

            i =
                when {
                    param < 0 -> {
                        sink.resetAttributes()
                        i + 1
                    }

                    param == 0 -> {
                        sink.resetAttributes()
                        i + 1
                    }

                    param == 1 -> {
                        sink.setBold(true)
                        i + 1
                    }

                    param == 2 -> {
                        sink.setFaint(true)
                        i + 1
                    }

                    param == 3 -> {
                        sink.setItalic(true)
                        i + 1
                    }

                    param == 4 -> {
                        dispatchUnderlineStyle(sink, state, i)
                    }

                    param == 5 || param == 6 -> {
                        sink.setBlink(true)
                        i + 1
                    }

                    param == 7 -> {
                        sink.setInverse(true)
                        i + 1
                    }

                    param == 8 -> {
                        sink.setConceal(true)
                        i + 1
                    }

                    param == 9 -> {
                        sink.setStrikethrough(true)
                        i + 1
                    }

                    param == 21 -> {
                        sink.setUnderlineStyle(SgrUnderlineStyle.DOUBLE)
                        i + 1
                    }

                    param == 22 -> {
                        sink.setBold(false)
                        sink.setFaint(false)
                        i + 1
                    }

                    param == 23 -> {
                        sink.setItalic(false)
                        i + 1
                    }

                    param == 24 -> {
                        sink.setUnderlineStyle(SgrUnderlineStyle.NONE)
                        i + 1
                    }

                    param == 25 -> {
                        sink.setBlink(false)
                        i + 1
                    }

                    param == 27 -> {
                        sink.setInverse(false)
                        i + 1
                    }

                    param == 28 -> {
                        sink.setConceal(false)
                        i + 1
                    }

                    param == 29 -> {
                        sink.setStrikethrough(false)
                        i + 1
                    }

                    param in 30..37 -> {
                        sink.setForegroundIndexed(param - 30)
                        i + 1
                    }

                    param == 38 ->
                        dispatchExtendedColor(
                            sink = sink,
                            state = state,
                            startIndex = i,
                            target = ColorTarget.FOREGROUND,
                        )

                    param == 39 -> {
                        sink.setForegroundDefault()
                        i + 1
                    }

                    param in 40..47 -> {
                        sink.setBackgroundIndexed(param - 40)
                        i + 1
                    }

                    param == 48 ->
                        dispatchExtendedColor(
                            sink = sink,
                            state = state,
                            startIndex = i,
                            target = ColorTarget.BACKGROUND,
                        )

                    param == 49 -> {
                        sink.setBackgroundDefault()
                        i + 1
                    }

                    param == 53 -> {
                        sink.setOverline(true)
                        i + 1
                    }

                    param == 55 -> {
                        sink.setOverline(false)
                        i + 1
                    }

                    param == 58 ->
                        dispatchExtendedColor(
                            sink = sink,
                            state = state,
                            startIndex = i,
                            target = ColorTarget.UNDERLINE,
                        )

                    param == 59 -> {
                        sink.setUnderlineColorDefault()
                        i + 1
                    }

                    param in 90..97 -> {
                        sink.setForegroundIndexed(8 + (param - 90))
                        i + 1
                    }

                    param in 100..107 -> {
                        sink.setBackgroundIndexed(8 + (param - 100))
                        i + 1
                    }

                    else -> i + 1
                }
        }
    }

    private fun dispatchExtendedColor(
        sink: TerminalCommandSink,
        state: ParserState,
        startIndex: Int,
        target: ColorTarget,
    ): Int {
        val modeIndex = startIndex + 1
        val mode = paramOrMissing(state, modeIndex)
        if (mode < 0) {
            return startIndex + 1
        }

        return when (mode) {
            5 ->
                dispatchIndexedColor(
                    sink = sink,
                    state = state,
                    startIndex = startIndex,
                    target = target,
                )

            2 ->
                dispatchRgbColor(
                    sink = sink,
                    state = state,
                    startIndex = startIndex,
                    target = target,
                )

            else -> startIndex + 2
        }
    }

    private fun dispatchUnderlineStyle(
        sink: TerminalCommandSink,
        state: ParserState,
        startIndex: Int,
    ): Int {
        val styleIndex = startIndex + 1
        if (!isColonOpened(state, styleIndex)) {
            sink.setUnderlineStyle(SgrUnderlineStyle.SINGLE)
            return startIndex + 1
        }

        when (paramOrMissing(state, styleIndex)) {
            0 -> sink.setUnderlineStyle(SgrUnderlineStyle.NONE)
            1 -> sink.setUnderlineStyle(SgrUnderlineStyle.SINGLE)
            2 -> sink.setUnderlineStyle(SgrUnderlineStyle.DOUBLE)
            3 -> sink.setUnderlineStyle(SgrUnderlineStyle.CURLY)
            4 -> sink.setUnderlineStyle(SgrUnderlineStyle.DOTTED)
            5 -> sink.setUnderlineStyle(SgrUnderlineStyle.DASHED)
        }

        return styleIndex + 1
    }

    private fun dispatchIndexedColor(
        sink: TerminalCommandSink,
        state: ParserState,
        startIndex: Int,
        target: ColorTarget,
    ): Int {
        val chainLength = getSubparameterChainLength(state, startIndex)
        val colorIndex = if (chainLength >= 4) startIndex + 3 else startIndex + 2
        val color = paramOrMissing(state, colorIndex)
        if (color !in 0..255) {
            return colorIndex + 1
        }

        when (target) {
            ColorTarget.FOREGROUND -> sink.setForegroundIndexed(color)
            ColorTarget.BACKGROUND -> sink.setBackgroundIndexed(color)
            ColorTarget.UNDERLINE -> sink.setUnderlineColorIndexed(color)
        }

        return colorIndex + 1
    }

    private fun dispatchRgbColor(
        sink: TerminalCommandSink,
        state: ParserState,
        startIndex: Int,
        target: ColorTarget,
    ): Int {
        val chainLength = getSubparameterChainLength(state, startIndex)
        val redIndex = if (chainLength >= 6) startIndex + 3 else startIndex + 2

        val red = paramOrMissing(state, redIndex)
        val green = paramOrMissing(state, redIndex + 1)
        val blue = paramOrMissing(state, redIndex + 2)

        if (red !in 0..255 || green !in 0..255 || blue !in 0..255) {
            return redIndex + 3
        }

        when (target) {
            ColorTarget.FOREGROUND -> sink.setForegroundRgb(red, green, blue)
            ColorTarget.BACKGROUND -> sink.setBackgroundRgb(red, green, blue)
            ColorTarget.UNDERLINE -> sink.setUnderlineColorRgb(red, green, blue)
        }

        return redIndex + 3
    }

    private fun paramOrMissing(
        state: ParserState,
        index: Int,
    ): Int =
        if (index in 0 until state.paramCount) {
            state.params[index]
        } else {
            -1
        }

    private fun isColonOpened(
        state: ParserState,
        index: Int,
    ): Boolean = index in 0..31 && ((state.subParameterMask ushr index) and 1) != 0

    private fun getSubparameterChainLength(
        state: ParserState,
        startIndex: Int,
    ): Int {
        var length = 1
        while (startIndex + length < state.paramCount && isColonOpened(state, startIndex + length)) {
            length++
        }
        return length
    }

    private enum class ColorTarget {
        FOREGROUND,
        BACKGROUND,
        UNDERLINE,
    }
}
