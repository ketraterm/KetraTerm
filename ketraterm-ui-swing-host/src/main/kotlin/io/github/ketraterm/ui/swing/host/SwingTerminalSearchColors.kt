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
package io.github.ketraterm.ui.swing.host

import java.awt.Color
import java.util.function.Consumer

/**
 * Prepared immutable colors for optional search chrome. Defaults retain the dark appearance.
 * Hosts resolve their theme on its owning thread and pass a snapshot to
 * [SwingTerminalSearchBar.refreshColors]. Dynamic Color subclasses are sampled at build time;
 * repainting never queries host theme state. All ARGB values, including transparency, are retained.
 * Use [create] and [copy] for named Kotlin or Java updates. Drafts are caller-confined and
 * callbacks run synchronously; later draft or host-color changes cannot mutate a snapshot.
 */
public class SwingTerminalSearchColors private constructor(
    builder: Builder,
) {
    /** Creates the default dark colors. */
    public constructor() : this(Builder())

    /** Shadow behind the rounded panel. */
    public val panelShadow: Color = Color(builder.panelShadow.rgb, true)

    /** Rounded panel background. */
    public val panelBackground: Color = Color(builder.panelBackground.rgb, true)

    /** Rounded panel outline. */
    public val panelBorder: Color = Color(builder.panelBorder.rgb, true)

    /** Query text, caret and resting button icons. */
    public val foreground: Color = Color(builder.foreground.rgb, true)

    /** Match count and search status text. */
    public val counterForeground: Color = Color(builder.counterForeground.rgb, true)

    /** Query field fill. */
    public val textFieldBackground: Color = Color(builder.textFieldBackground.rgb, true)

    /** Unfocused query field outline. */
    public val textFieldBorder: Color = Color(builder.textFieldBorder.rgb, true)

    /** Focused query field outline. */
    public val textFieldFocusBorder: Color = Color(builder.textFieldFocusBorder.rgb, true)

    /** Empty query placeholder. */
    public val textFieldPlaceholder: Color = Color(builder.textFieldPlaceholder.rgb, true)

    /** Query field search icon. */
    public val searchIconForeground: Color = Color(builder.searchIconForeground.rgb, true)

    /** Hovered button fill. */
    public val buttonHoverBackground: Color = Color(builder.buttonHoverBackground.rgb, true)

    /** Pressed button fill. */
    public val buttonPressedBackground: Color = Color(builder.buttonPressedBackground.rgb, true)

    /** Selected case toggle fill. */
    public val buttonSelectedBackground: Color = Color(builder.buttonSelectedBackground.rgb, true)

    /** Selected case toggle text. */
    public val buttonSelectedForeground: Color = Color(builder.buttonSelectedForeground.rgb, true)

    /** Creates a detached mutable draft. */
    public fun toBuilder(): Builder = Builder(this)

    /** Builds a new snapshot after synchronously applying [configure] to a detached draft. */
    public fun copy(configure: Consumer<Builder>): SwingTerminalSearchColors = toBuilder().also { configure.accept(it) }.build()

    /** Caller-confined draft; building samples all colors without retaining this draft. */
    public class Builder internal constructor(
        source: SwingTerminalSearchColors? = null,
    ) {
        /** Draft value for [SwingTerminalSearchColors.panelShadow]. */
        public var panelShadow: Color = source?.panelShadow ?: Color(0x70000000, true)

        /** Draft value for [SwingTerminalSearchColors.panelBackground]. */
        public var panelBackground: Color = source?.panelBackground ?: Color(0xF01F2227.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.panelBorder]. */
        public var panelBorder: Color = source?.panelBorder ?: Color(0x55414852, true)

        /** Draft value for [SwingTerminalSearchColors.foreground]. */
        public var foreground: Color = source?.foreground ?: Color(0xFFE8EAED.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.counterForeground]. */
        public var counterForeground: Color = source?.counterForeground ?: Color(0xFFA4ABB6.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.textFieldBackground]. */
        public var textFieldBackground: Color = source?.textFieldBackground ?: Color(0xFF25282E.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.textFieldBorder]. */
        public var textFieldBorder: Color = source?.textFieldBorder ?: Color(0x4A4B5563, true)

        /** Draft value for [SwingTerminalSearchColors.textFieldFocusBorder]. */
        public var textFieldFocusBorder: Color = source?.textFieldFocusBorder ?: Color(0xFF3574F0.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.textFieldPlaceholder]. */
        public var textFieldPlaceholder: Color = source?.textFieldPlaceholder ?: Color(0xFF8B929D.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.searchIconForeground]. */
        public var searchIconForeground: Color = source?.searchIconForeground ?: Color(0xFFB6BBC4.toInt(), true)

        /** Draft value for [SwingTerminalSearchColors.buttonHoverBackground]. */
        public var buttonHoverBackground: Color = source?.buttonHoverBackground ?: Color(0x18FFFFFF, true)

        /** Draft value for [SwingTerminalSearchColors.buttonPressedBackground]. */
        public var buttonPressedBackground: Color = source?.buttonPressedBackground ?: Color(0x2CFFFFFF, true)

        /** Draft value for [SwingTerminalSearchColors.buttonSelectedBackground]. */
        public var buttonSelectedBackground: Color = source?.buttonSelectedBackground ?: Color(0x553574F0, true)

        /** Draft value for [SwingTerminalSearchColors.buttonSelectedForeground]. */
        public var buttonSelectedForeground: Color = source?.buttonSelectedForeground ?: Color(0xFFFFFFFF.toInt(), true)

        /** Freezes the current ARGB values into a new snapshot. */
        public fun build(): SwingTerminalSearchColors = SwingTerminalSearchColors(this)
    }

    public companion object {
        /** Creates a default mutable draft. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Builds a snapshot after synchronously configuring a fresh default draft. */
        @JvmStatic
        public fun create(configure: Consumer<Builder>): SwingTerminalSearchColors = Builder().also { configure.accept(it) }.build()
    }
}
