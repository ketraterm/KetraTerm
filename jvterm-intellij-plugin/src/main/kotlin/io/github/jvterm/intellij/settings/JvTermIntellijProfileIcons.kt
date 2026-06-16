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
package io.github.jvterm.intellij.settings

import com.intellij.openapi.util.IconLoader
import io.github.jvterm.workspace.TerminalProfileKind
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.EnumMap
import javax.swing.Icon

private const val PROFILE_ICON_RESOURCE_DIR = "/icons"
private const val PROFILE_ICON_WIDTH = 16
private const val PROFILE_ICON_HEIGHT = 16

/**
 * IntelliJ-side loader for shell profile icons keyed by shared profile kind.
 */
internal class JvTermIntellijProfileIcons {
    private val icons = EnumMap<TerminalProfileKind, Icon>(TerminalProfileKind::class.java)

    /**
     * Returns the icon for [profileKind].
     *
     * @param profileKind shared shell profile presentation kind.
     * @return cached IntelliJ icon.
     */
    fun icon(profileKind: TerminalProfileKind): Icon =
        icons.getOrPut(profileKind) {
            FixedSizeIcon(
                IconLoader.getIcon(resourcePath(profileKind), JvTermIntellijProfileIcons::class.java),
                PROFILE_ICON_WIDTH,
                PROFILE_ICON_HEIGHT,
            )
        }

    private fun resourcePath(profileKind: TerminalProfileKind): String =
        when (profileKind) {
            TerminalProfileKind.POWERSHELL -> "$PROFILE_ICON_RESOURCE_DIR/profile-powershell.svg"
            TerminalProfileKind.COMMAND_PROMPT -> "$PROFILE_ICON_RESOURCE_DIR/profile-command-prompt.svg"
            TerminalProfileKind.GIT_BASH -> "$PROFILE_ICON_RESOURCE_DIR/profile-git-bash.svg"
            TerminalProfileKind.UBUNTU -> "$PROFILE_ICON_RESOURCE_DIR/profile-ubuntu.svg"
            TerminalProfileKind.BASH -> "$PROFILE_ICON_RESOURCE_DIR/profile-bash.svg"
            TerminalProfileKind.ZSH -> "$PROFILE_ICON_RESOURCE_DIR/profile-zsh.svg"
            TerminalProfileKind.FISH -> "$PROFILE_ICON_RESOURCE_DIR/profile-fish.svg"
            TerminalProfileKind.NUSHELL -> "$PROFILE_ICON_RESOURCE_DIR/profile-nushell.svg"
            TerminalProfileKind.WSL -> "$PROFILE_ICON_RESOURCE_DIR/profile-wsl.svg"
            TerminalProfileKind.UNIX_SHELL,
            TerminalProfileKind.DEFAULT,
            -> "$PROFILE_ICON_RESOURCE_DIR/profile-shell.svg"
        }
}

private class FixedSizeIcon(
    private val delegate: Icon,
    private val width: Int,
    private val height: Int,
) : Icon {
    override fun getIconWidth(): Int = width

    override fun getIconHeight(): Int = height

    override fun paintIcon(
        component: Component?,
        graphics: Graphics,
        x: Int,
        y: Int,
    ) {
        val delegateWidth = delegate.iconWidth
        val delegateHeight = delegate.iconHeight
        if (delegateWidth <= 0 || delegateHeight <= 0) return

        val scale = minOf(width.toDouble() / delegateWidth.toDouble(), height.toDouble() / delegateHeight.toDouble())
        val scaledWidth = (delegateWidth * scale).toInt()
        val scaledHeight = (delegateHeight * scale).toInt()
        val offsetX = x + (width - scaledWidth) / 2
        val offsetY = y + (height - scaledHeight) / 2
        val g2 = graphics.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.translate(offsetX, offsetY)
            g2.scale(scale, scale)
            delegate.paintIcon(component, g2, 0, 0)
        } finally {
            g2.dispose()
        }
    }
}
