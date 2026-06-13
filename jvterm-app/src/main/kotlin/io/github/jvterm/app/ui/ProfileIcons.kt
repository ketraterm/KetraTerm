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
package io.github.jvterm.app.ui

import com.formdev.flatlaf.extras.FlatSVGIcon
import io.github.jvterm.workspace.TerminalProfileKind
import java.awt.AlphaComposite
import java.awt.Graphics2D
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

    fun paint(
        g2: Graphics2D,
        profileKind: TerminalProfileKind,
        x: Int,
        y: Int,
        selected: Boolean,
        highlighted: Boolean,
    ) {
        val previousComposite = g2.composite
        g2.composite =
            AlphaComposite.SrcOver.derive(
                if (selected || highlighted) ACTIVE_OPACITY else INACTIVE_OPACITY,
            )
        try {
            icon(profileKind).paintIcon(null, g2, x, y)
        } finally {
            g2.composite = previousComposite
        }
    }

    internal fun icon(profileKind: TerminalProfileKind): Icon =
        icons.getOrPut(profileKind) {
            FlatSVGIcon(resourcePath(profileKind), ICON_WIDTH, ICON_HEIGHT)
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
        private const val RESOURCE_DIR = "io/github/jvterm/app/icons"
        private const val ICON_WIDTH = 14
        private const val ICON_HEIGHT = 12
        private const val ACTIVE_OPACITY = 1f
        private const val INACTIVE_OPACITY = 0.63f
    }
}
