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
package io.github.ketraterm.benchmark

import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent

/**
 * No-op sink for parser-only benchmarks. Discards all commands to measure
 * pure parser throughput.
 */
internal class NoOpCommandSink : TerminalCommandSink {
    override fun writeCodepoint(codepoint: Int) {}

    override fun writeCluster(
        codepoints: IntArray,
        length: Int,
    ) {}

    override fun appendToPreviousCluster(codepoint: Int) {}

    override fun bell() {}

    override fun backspace() {}

    override fun tab() {}

    override fun lineFeed() {}

    override fun carriageReturn() {}

    override fun reverseIndex() {}

    override fun nextLine() {}

    override fun softReset() {}

    override fun resetTerminal() {}

    override fun decaln() {}

    override fun saveCursor() {}

    override fun restoreCursor() {}

    override fun setCursorStyle(style: Int) {}

    override fun cursorUp(n: Int) {}

    override fun cursorDown(n: Int) {}

    override fun cursorForward(n: Int) {}

    override fun cursorBackward(n: Int) {}

    override fun cursorNextLine(n: Int) {}

    override fun cursorPreviousLine(n: Int) {}

    override fun cursorForwardTabs(n: Int) {}

    override fun cursorBackwardTabs(n: Int) {}

    override fun setCursorColumn(col: Int) {}

    override fun setCursorRow(row: Int) {}

    override fun setCursorAbsolute(
        row: Int,
        col: Int,
    ) {}

    override fun setScrollRegion(
        top: Int,
        bottom: Int,
    ) {}

    override fun setLeftRightMargins(
        left: Int,
        right: Int,
    ) {}

    override fun eraseInDisplay(
        mode: Int,
        selective: Boolean,
    ) {}

    override fun eraseInLine(
        mode: Int,
        selective: Boolean,
    ) {}

    override fun insertLines(n: Int) {}

    override fun deleteLines(n: Int) {}

    override fun insertCharacters(n: Int) {}

    override fun deleteCharacters(n: Int) {}

    override fun eraseCharacters(n: Int) {}

    override fun scrollUp(n: Int) {}

    override fun scrollDown(n: Int) {}

    override fun setTabStop() {}

    override fun clearTabStop() {}

    override fun clearAllTabStops() {}

    override fun setAnsiMode(
        mode: Int,
        enable: Boolean,
    ) {}

    override fun setDecMode(
        mode: Int,
        enable: Boolean,
    ) {}

    override fun setKeyModifierOption(
        resource: Int,
        value: Int,
    ) {}

    override fun resetKeyModifierOption(resource: Int) {}

    override fun resetKeyModifierOptions() {}

    override fun setKeyFormatOption(
        resource: Int,
        value: Int,
    ) {}

    override fun resetKeyFormatOption(resource: Int) {}

    override fun resetKeyFormatOptions() {}

    override fun applyKittyKeyboardFlags(
        flags: Int,
        applicationMode: Int,
    ) {}

    override fun pushKittyKeyboardFlags(flags: Int) {}

    override fun popKittyKeyboardFlags(count: Int) {}

    override fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    ) {}

    override fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    ) {}

    override fun requestWindowReport(mode: Int) {}

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) {}

    override fun moveWindow(
        x: Int,
        y: Int,
    ) {}

    override fun minimizeWindow() {}

    override fun deminimizeWindow() {}

    override fun raiseWindow() {}

    override fun lowerWindow() {}

    override fun setMaximized(maximize: Boolean) {}

    override fun pushTitleStack(scope: Int) {}

    override fun popTitleStack(scope: Int) {}

    override fun resetAttributes() {}

    override fun setBold(enabled: Boolean) {}

    override fun setFaint(enabled: Boolean) {}

    override fun setItalic(enabled: Boolean) {}

    override fun setUnderlineStyle(style: Int) {}

    override fun setBlink(enabled: Boolean) {}

    override fun setInverse(enabled: Boolean) {}

    override fun setConceal(enabled: Boolean) {}

    override fun setStrikethrough(enabled: Boolean) {}

    override fun setOverline(enabled: Boolean) {}

    override fun setSelectiveEraseProtection(enabled: Boolean) {}

    override fun setForegroundDefault() {}

    override fun setBackgroundDefault() {}

    override fun setUnderlineColorDefault() {}

    override fun setForegroundIndexed(index: Int) {}

    override fun setBackgroundIndexed(index: Int) {}

    override fun setUnderlineColorIndexed(index: Int) {}

    override fun setForegroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {}

    override fun setBackgroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {}

    override fun setUnderlineColorRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {}

    override fun setWindowTitle(title: String) {}

    override fun setIconTitle(title: String) {}

    override fun setIconAndWindowTitle(title: String) {}

    override fun setCurrentWorkingDirectoryUri(uri: String) {}

    override fun startHyperlink(
        uri: String,
        id: String?,
    ) {}

    override fun endHyperlink() {}

    override fun setPaletteColor(
        index: Int,
        color: Int,
    ) {}

    override fun queryPaletteColor(index: Int) {}

    override fun setDynamicColor(
        target: Int,
        color: Int,
    ) {}

    override fun queryDynamicColor(target: Int) {}

    override fun queryStatusString(query: String) {}

    override fun queryTerminfo(rawPayload: String) {}

    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {}

    override fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    ) {}
}
