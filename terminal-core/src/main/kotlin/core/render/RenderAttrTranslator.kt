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
import com.gagik.core.model.AttributeColor
import com.gagik.core.model.AttributeColorKind
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
    ): Long {
        val foreground = AttributeCodec.foregroundColor(primaryAttr)
        val background = AttributeCodec.backgroundColor(primaryAttr)
        return TerminalRenderAttrs.pack(
            foregroundKind = foreground.toRenderKind(),
            foregroundValue = foreground.value,
            backgroundKind = background.toRenderKind(),
            backgroundValue = background.value,
            bold = AttributeCodec.isBold(primaryAttr),
            faint = AttributeCodec.isFaint(primaryAttr),
            italic = AttributeCodec.isItalic(primaryAttr),
            underlineStyle = AttributeCodec.underlineStyle(extendedAttr).toRenderUnderline(),
            blink = AttributeCodec.isBlink(primaryAttr),
            inverse = AttributeCodec.isInverse(primaryAttr) xor reverseVideo,
            invisible = AttributeCodec.isConceal(extendedAttr),
            strikethrough = AttributeCodec.isStrikethrough(extendedAttr),
        )
    }

    fun toRenderExtraAttrWord(extendedAttr: Long): Long {
        val underlineColor = AttributeCodec.underlineAttributeColor(extendedAttr)
        return TerminalRenderExtraAttrs.pack(
            underlineColorKind = underlineColor.toRenderKind(),
            underlineColorValue = underlineColor.value,
            overline = AttributeCodec.isOverline(extendedAttr),
        )
    }

    private fun AttributeColor.toRenderKind(): Int =
        when (kind) {
            AttributeColorKind.DEFAULT -> TerminalRenderColorKind.DEFAULT
            AttributeColorKind.INDEXED -> TerminalRenderColorKind.INDEXED
            AttributeColorKind.RGB -> TerminalRenderColorKind.RGB
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
