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
package com.gagik.core.render

import com.gagik.core.codec.AttributeCodec
import com.gagik.core.model.UnderlineStyle
import com.gagik.terminal.render.api.TerminalRenderAttrs
import com.gagik.terminal.render.api.TerminalRenderColorKind
import com.gagik.terminal.render.api.TerminalRenderExtraAttrs
import com.gagik.terminal.render.api.TerminalRenderUnderline

/**
 * Translates core's private attribute packing into the stable public render ABI.
 */
internal class RenderAttrTranslator {
    fun toRenderAttrWord(
        primaryAttr: Long,
        extendedAttr: Long,
        reverseVideo: Boolean,
    ): Long =
        TerminalRenderAttrs.pack(
            foregroundKind = AttributeCodec.foregroundColorKind(primaryAttr).toRenderColorKind(),
            foregroundValue = AttributeCodec.foregroundColorValue(primaryAttr),
            backgroundKind = AttributeCodec.backgroundColorKind(primaryAttr).toRenderColorKind(),
            backgroundValue = AttributeCodec.backgroundColorValue(primaryAttr),
            bold = AttributeCodec.isBold(primaryAttr),
            faint = AttributeCodec.isFaint(primaryAttr),
            italic = AttributeCodec.isItalic(primaryAttr),
            underlineStyle = AttributeCodec.underlineStyle(extendedAttr).toRenderUnderline(),
            blink = AttributeCodec.isBlink(primaryAttr),
            inverse = AttributeCodec.isInverse(primaryAttr) xor reverseVideo,
            invisible = AttributeCodec.isConceal(extendedAttr),
            strikethrough = AttributeCodec.isStrikethrough(extendedAttr),
        )

    fun toRenderExtraAttrWord(extendedAttr: Long): Long =
        TerminalRenderExtraAttrs.pack(
            underlineColorKind = AttributeCodec.underlineAttributeColorKind(extendedAttr).toRenderColorKind(),
            underlineColorValue = AttributeCodec.underlineAttributeColorValue(extendedAttr),
            overline = AttributeCodec.isOverline(extendedAttr),
        )

    private fun Int.toRenderColorKind(): Int =
        when (this) {
            AttributeCodec.COLOR_KIND_INDEXED -> TerminalRenderColorKind.INDEXED
            AttributeCodec.COLOR_KIND_RGB -> TerminalRenderColorKind.RGB
            else -> TerminalRenderColorKind.DEFAULT
        }

    private fun UnderlineStyle.toRenderUnderline(): Int =
        when (this) {
            UnderlineStyle.NONE -> TerminalRenderUnderline.NONE
            UnderlineStyle.SINGLE -> TerminalRenderUnderline.SINGLE
            UnderlineStyle.DOUBLE -> TerminalRenderUnderline.DOUBLE
            UnderlineStyle.CURLY -> TerminalRenderUnderline.CURLY
            UnderlineStyle.DOTTED -> TerminalRenderUnderline.DOTTED
            UnderlineStyle.DASHED -> TerminalRenderUnderline.DASHED
        }
}
