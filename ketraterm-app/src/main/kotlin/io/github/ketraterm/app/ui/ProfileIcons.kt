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
package io.github.ketraterm.app.ui

import com.formdev.flatlaf.extras.FlatSVGIcon
import io.github.ketraterm.workspace.TerminalProfileKind
import java.awt.Component
import java.awt.Graphics
import java.util.*
import javax.swing.Icon

/**
 * Loads and paints standalone tab profile icons from SVG resources.
 *
 * The tab bar owns placement and state. Glyph design lives in vector assets so
 * profile icon changes do not require editing Java2D drawing code.
 */
internal class ProfileIcons {
    private val icons = EnumMap<TerminalProfileKind, Icon>(TerminalProfileKind::class.java)

    internal fun icon(profileKind: TerminalProfileKind): Icon =
        icons.getOrPut(profileKind) {
            val source = FlatSVGIcon(resourcePath(profileKind))
            // The selected terminal palette can differ from FlatLaf's light/dark theme.
            source.colorFilter =
                FlatSVGIcon.ColorFilter { color ->
                    when (color.rgb and 0xFFFFFF) {
                        0x6E6E6E, 0xCED0D6 -> Chrome.textPrimary
                        else -> color
                    }
                }
            val scale = minOf(ICON_WIDTH.toFloat() / source.iconWidth, ICON_HEIGHT.toFloat() / source.iconHeight)
            val glyph = source.derive(scale)
            object : Icon {
                override fun getIconWidth(): Int = ICON_WIDTH

                override fun getIconHeight(): Int = ICON_HEIGHT

                override fun paintIcon(
                    component: Component?,
                    graphics: Graphics,
                    x: Int,
                    y: Int,
                ) {
                    glyph.paintIcon(component, graphics, x + (ICON_WIDTH - glyph.iconWidth) / 2, y + (ICON_HEIGHT - glyph.iconHeight) / 2)
                }
            }
        }

    private fun resourcePath(profileKind: TerminalProfileKind): String =
        when (profileKind) {
            TerminalProfileKind.POWERSHELL -> "$RESOURCE_DIR/profile-powershell.svg"
            TerminalProfileKind.COMMAND_PROMPT -> "$RESOURCE_DIR/profile-command-prompt.svg"
            TerminalProfileKind.GIT_BASH -> "$RESOURCE_DIR/profile-git-bash.svg"
            TerminalProfileKind.UBUNTU -> "$RESOURCE_DIR/profile-ubuntu.svg"
            TerminalProfileKind.BASH -> "$RESOURCE_DIR/profile-bash.svg"
            TerminalProfileKind.ZSH -> "$RESOURCE_DIR/profile-zsh.svg"
            TerminalProfileKind.FISH -> "$RESOURCE_DIR/profile-fish.svg"
            TerminalProfileKind.NUSHELL -> "$RESOURCE_DIR/profile-nushell.svg"
            TerminalProfileKind.WSL -> "$RESOURCE_DIR/profile-wsl.svg"
            TerminalProfileKind.UNIX_SHELL,
            TerminalProfileKind.DEFAULT,
            -> "$RESOURCE_DIR/profile-shell.svg"
        }

    private companion object {
        private const val RESOURCE_DIR = "io/github/ketraterm/app/icons"
        private const val ICON_WIDTH = 14
        private const val ICON_HEIGHT = 12
    }
}
