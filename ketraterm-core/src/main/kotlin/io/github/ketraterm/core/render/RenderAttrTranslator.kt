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
package io.github.ketraterm.core.render

import io.github.ketraterm.core.codec.AttributeCodec
import io.github.ketraterm.core.model.UnderlineStyle

/**
 * Translates core's private attribute packing into the stable public render ABI.
 */
internal class RenderAttrTranslator {
    fun toRenderAttrWord(
        primaryAttr: Long,
        extendedAttr: Long,
        reverseVideo: Boolean,
    ): Long =
        io.github.ketraterm.render.api.TerminalRenderAttrs.pack(
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
        io.github.ketraterm.render.api.TerminalRenderExtraAttrs.pack(
            underlineColorKind = AttributeCodec.underlineColorKind(extendedAttr).toRenderColorKind(),
            underlineColorValue = AttributeCodec.underlineColorValue(extendedAttr),
            overline = AttributeCodec.isOverline(extendedAttr),
        )

    private fun Int.toRenderColorKind(): Int =
        when (this) {
            AttributeCodec.COLOR_KIND_INDEXED -> io.github.ketraterm.render.api.TerminalRenderColorKind.INDEXED
            AttributeCodec.COLOR_KIND_RGB -> io.github.ketraterm.render.api.TerminalRenderColorKind.RGB
            else -> io.github.ketraterm.render.api.TerminalRenderColorKind.DEFAULT
        }

    private fun UnderlineStyle.toRenderUnderline(): Int =
        when (this) {
            UnderlineStyle.NONE -> io.github.ketraterm.render.api.TerminalRenderUnderline.NONE
            UnderlineStyle.SINGLE -> io.github.ketraterm.render.api.TerminalRenderUnderline.SINGLE
            UnderlineStyle.DOUBLE -> io.github.ketraterm.render.api.TerminalRenderUnderline.DOUBLE
            UnderlineStyle.CURLY -> io.github.ketraterm.render.api.TerminalRenderUnderline.CURLY
            UnderlineStyle.DOTTED -> io.github.ketraterm.render.api.TerminalRenderUnderline.DOTTED
            UnderlineStyle.DASHED -> io.github.ketraterm.render.api.TerminalRenderUnderline.DASHED
        }
}
