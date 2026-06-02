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
package com.gagik.parser.ansi

import com.gagik.parser.spi.TerminalCommandSink

internal class RecordingTerminalCommandSink : TerminalCommandSink {
    val events = ArrayList<String>()

    override fun writeCodepoint(codepoint: Int) {
        events += "writeCodepoint:$codepoint"
    }

    override fun writeCluster(
        codepoints: IntArray,
        length: Int,
    ) {
        events += "writeCluster:$length:${codepoints.take(length).joinToString(":")}"
    }

    override fun appendToPreviousCluster(codepoint: Int) {
        events += "appendToPreviousCluster:$codepoint"
    }

    override fun bell() {
        events += "bell"
    }

    override fun backspace() {
        events += "backspace"
    }

    override fun tab() {
        events += "tab"
    }

    override fun lineFeed() {
        events += "lineFeed"
    }

    override fun carriageReturn() {
        events += "carriageReturn"
    }

    override fun reverseIndex() {
        events += "reverseIndex"
    }

    override fun nextLine() {
        events += "nextLine"
    }

    override fun softReset() {
        events += "softReset"
    }

    override fun resetTerminal() {
        events += "resetTerminal"
    }

    override fun decaln() {
        events += "decaln"
    }

    override fun saveCursor() {
        events += "saveCursor"
    }

    override fun restoreCursor() {
        events += "restoreCursor"
    }

    override fun setCursorStyle(style: Int) {
        events += "setCursorStyle:$style"
    }

    override fun cursorUp(n: Int) {
        events += "cursorUp:$n"
    }

    override fun cursorDown(n: Int) {
        events += "cursorDown:$n"
    }

    override fun cursorForward(n: Int) {
        events += "cursorForward:$n"
    }

    override fun cursorBackward(n: Int) {
        events += "cursorBackward:$n"
    }

    override fun cursorNextLine(n: Int) {
        events += "cursorNextLine:$n"
    }

    override fun cursorPreviousLine(n: Int) {
        events += "cursorPreviousLine:$n"
    }

    override fun cursorForwardTabs(n: Int) {
        events += "cursorForwardTabs:$n"
    }

    override fun cursorBackwardTabs(n: Int) {
        events += "cursorBackwardTabs:$n"
    }

    override fun setCursorColumn(col: Int) {
        events += "setCursorColumn:$col"
    }

    override fun setCursorRow(row: Int) {
        events += "setCursorRow:$row"
    }

    override fun setCursorAbsolute(
        row: Int,
        col: Int,
    ) {
        events += "setCursorAbsolute:$row:$col"
    }

    override fun setScrollRegion(
        top: Int,
        bottom: Int,
    ) {
        events += "setScrollRegion:$top:$bottom"
    }

    override fun setLeftRightMargins(
        left: Int,
        right: Int,
    ) {
        events += "setLeftRightMargins:$left:$right"
    }

    override fun eraseInDisplay(
        mode: Int,
        selective: Boolean,
    ) {
        events += "eraseInDisplay:$mode:$selective"
    }

    override fun eraseInLine(
        mode: Int,
        selective: Boolean,
    ) {
        events += "eraseInLine:$mode:$selective"
    }

    override fun insertLines(n: Int) {
        events += "insertLines:$n"
    }

    override fun deleteLines(n: Int) {
        events += "deleteLines:$n"
    }

    override fun insertCharacters(n: Int) {
        events += "insertCharacters:$n"
    }

    override fun deleteCharacters(n: Int) {
        events += "deleteCharacters:$n"
    }

    override fun eraseCharacters(n: Int) {
        events += "eraseCharacters:$n"
    }

    override fun scrollUp(n: Int) {
        events += "scrollUp:$n"
    }

    override fun scrollDown(n: Int) {
        events += "scrollDown:$n"
    }

    override fun setTabStop() {
        events += "setTabStop"
    }

    override fun clearTabStop() {
        events += "clearTabStop"
    }

    override fun clearAllTabStops() {
        events += "clearAllTabStops"
    }

    override fun setAnsiMode(
        mode: Int,
        enable: Boolean,
    ) {
        events += "setAnsiMode:$mode:$enable"
    }

    override fun setDecMode(
        mode: Int,
        enable: Boolean,
    ) {
        events += "setDecMode:$mode:$enable"
    }

    override fun setKeyModifierOption(
        resource: Int,
        value: Int,
    ) {
        events += "setKeyModifierOption:$resource:$value"
    }

    override fun resetKeyModifierOption(resource: Int) {
        events += "resetKeyModifierOption:$resource"
    }

    override fun resetKeyModifierOptions() {
        events += "resetKeyModifierOptions"
    }

    override fun setKeyFormatOption(
        resource: Int,
        value: Int,
    ) {
        events += "setKeyFormatOption:$resource:$value"
    }

    override fun resetKeyFormatOption(resource: Int) {
        events += "resetKeyFormatOption:$resource"
    }

    override fun resetKeyFormatOptions() {
        events += "resetKeyFormatOptions"
    }

    override fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    ) {
        events += "requestDeviceStatusReport:$mode:$decPrivate"
    }

    override fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    ) {
        events += "requestDeviceAttributes:$kind:$parameter"
    }

    override fun requestWindowReport(mode: Int) {
        events += "requestWindowReport:$mode"
    }

    override fun pushTitleStack(scope: Int) {
        events += "pushTitleStack:$scope"
    }

    override fun popTitleStack(scope: Int) {
        events += "popTitleStack:$scope"
    }

    override fun resetAttributes() {
        events += "resetAttributes"
    }

    override fun setBold(enabled: Boolean) {
        events += "setBold:$enabled"
    }

    override fun setFaint(enabled: Boolean) {
        events += "setFaint:$enabled"
    }

    override fun setItalic(enabled: Boolean) {
        events += "setItalic:$enabled"
    }

    override fun setUnderlineStyle(style: Int) {
        events += "setUnderlineStyle:$style"
    }

    override fun setBlink(enabled: Boolean) {
        events += "setBlink:$enabled"
    }

    override fun setInverse(enabled: Boolean) {
        events += "setInverse:$enabled"
    }

    override fun setConceal(enabled: Boolean) {
        events += "setConceal:$enabled"
    }

    override fun setStrikethrough(enabled: Boolean) {
        events += "setStrikethrough:$enabled"
    }

    override fun setOverline(enabled: Boolean) {
        events += "setOverline:$enabled"
    }

    override fun setSelectiveEraseProtection(enabled: Boolean) {
        events += "setSelectiveEraseProtection:$enabled"
    }

    override fun setForegroundDefault() {
        events += "setForegroundDefault"
    }

    override fun setBackgroundDefault() {
        events += "setBackgroundDefault"
    }

    override fun setUnderlineColorDefault() {
        events += "setUnderlineColorDefault"
    }

    override fun setForegroundIndexed(index: Int) {
        events += "setForegroundIndexed:$index"
    }

    override fun setBackgroundIndexed(index: Int) {
        events += "setBackgroundIndexed:$index"
    }

    override fun setUnderlineColorIndexed(index: Int) {
        events += "setUnderlineColorIndexed:$index"
    }

    override fun setForegroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        events += "setForegroundRgb:$red:$green:$blue"
    }

    override fun setBackgroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        events += "setBackgroundRgb:$red:$green:$blue"
    }

    override fun setUnderlineColorRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        events += "setUnderlineColorRgb:$red:$green:$blue"
    }

    override fun setWindowTitle(title: String) {
        events += "setWindowTitle:$title"
    }

    override fun setIconTitle(title: String) {
        events += "setIconTitle:$title"
    }

    override fun setIconAndWindowTitle(title: String) {
        events += "setIconAndWindowTitle:$title"
    }

    override fun startHyperlink(
        uri: String,
        id: String?,
    ) {
        events += "startHyperlink:$uri:${id ?: "null"}"
    }

    override fun endHyperlink() {
        events += "endHyperlink"
    }
}
