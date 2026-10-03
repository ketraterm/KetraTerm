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
package consumer;

import io.github.ketraterm.core.TerminalBuffers;
import io.github.ketraterm.core.api.TerminalLine;
import io.github.ketraterm.host.HostCommandAdapter;
import io.github.ketraterm.host.HostEventSink;
import io.github.ketraterm.parser.api.TerminalParsers;
import io.github.ketraterm.parser.spi.TerminalCommandSink;
import java.nio.charset.StandardCharsets;

public final class JavaConsumer {
    // Deliberately concrete: a new required parser callback must fail this retained implementation.
    // Commands outside this fixture's supported slice fail instead of silently disappearing.
    public static final class Sink implements TerminalCommandSink {
        public final StringBuilder text = new StringBuilder();
        public int bells;
        @Override public boolean isAlternateScreenActive() { return false; }
        @Override public void requestModeStatus(int p0, boolean p1) { throw new AssertionError("Unexpected required command: requestModeStatus"); }
        @Override public void writeCodepoint(int p0) { text.appendCodePoint(p0); }
        @Override public void writeCluster(int[] p0, int p1) { for (int i = 0; i < p1; i++) text.appendCodePoint(p0[i]); }
        @Override public void updatePreviousCluster(int[] p0, int p1) { throw new AssertionError("Unexpected required command: updatePreviousCluster"); }
        @Override public void bell() { bells++; }
        @Override public void backspace() { throw new AssertionError("Unexpected required command: backspace"); }
        @Override public void tab() { throw new AssertionError("Unexpected required command: tab"); }
        @Override public void lineFeed() { throw new AssertionError("Unexpected required command: lineFeed"); }
        @Override public void carriageReturn() { throw new AssertionError("Unexpected required command: carriageReturn"); }
        @Override public void reverseIndex() { throw new AssertionError("Unexpected required command: reverseIndex"); }
        @Override public void nextLine() { throw new AssertionError("Unexpected required command: nextLine"); }
        @Override public void softReset() { throw new AssertionError("Unexpected required command: softReset"); }
        @Override public void resetTerminal() { throw new AssertionError("Unexpected required command: resetTerminal"); }
        @Override public void decaln() { throw new AssertionError("Unexpected required command: decaln"); }
        @Override public void saveCursor() { throw new AssertionError("Unexpected required command: saveCursor"); }
        @Override public boolean saveCursorOrResetMargins() { throw new AssertionError("Unexpected required command: saveCursorOrResetMargins"); }
        @Override public void restoreCursor() { throw new AssertionError("Unexpected required command: restoreCursor"); }
        @Override public void setCursorStyle(int p0) { throw new AssertionError("Unexpected required command: setCursorStyle"); }
        @Override public void cursorUp(int p0) { throw new AssertionError("Unexpected required command: cursorUp"); }
        @Override public void cursorDown(int p0) { throw new AssertionError("Unexpected required command: cursorDown"); }
        @Override public void cursorForward(int p0) { throw new AssertionError("Unexpected required command: cursorForward"); }
        @Override public void cursorBackward(int p0) { throw new AssertionError("Unexpected required command: cursorBackward"); }
        @Override public void cursorNextLine(int p0) { throw new AssertionError("Unexpected required command: cursorNextLine"); }
        @Override public void cursorPreviousLine(int p0) { throw new AssertionError("Unexpected required command: cursorPreviousLine"); }
        @Override public void cursorForwardTabs(int p0) { throw new AssertionError("Unexpected required command: cursorForwardTabs"); }
        @Override public void cursorBackwardTabs(int p0) { throw new AssertionError("Unexpected required command: cursorBackwardTabs"); }
        @Override public void setCursorColumn(int p0) { throw new AssertionError("Unexpected required command: setCursorColumn"); }
        @Override public void setCursorRow(int p0) { throw new AssertionError("Unexpected required command: setCursorRow"); }
        @Override public void setCursorAbsolute(int p0, int p1) { throw new AssertionError("Unexpected required command: setCursorAbsolute"); }
        @Override public void setScrollRegion(int p0, int p1) { throw new AssertionError("Unexpected required command: setScrollRegion"); }
        @Override public void setLeftRightMargins(int p0, int p1) { throw new AssertionError("Unexpected required command: setLeftRightMargins"); }
        @Override public void eraseInDisplay(int p0, boolean p1) { throw new AssertionError("Unexpected required command: eraseInDisplay"); }
        @Override public void eraseInLine(int p0, boolean p1) { throw new AssertionError("Unexpected required command: eraseInLine"); }
        @Override public void eraseRectangle(int p0, int p1, int p2, int p3, boolean p4) { throw new AssertionError("Unexpected required command: eraseRectangle"); }
        @Override public void fillRectangle(int p0, int p1, int p2, int p3, int p4) { throw new AssertionError("Unexpected required command: fillRectangle"); }
        @Override public void copyRectangle(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) { throw new AssertionError("Unexpected required command: copyRectangle"); }
        @Override public void requestRectangleChecksum(int p0, int p1, int p2, int p3, int p4, int p5) { throw new AssertionError("Unexpected required command: requestRectangleChecksum"); }
        @Override public void setAttributeChangeExtent(int p0) { throw new AssertionError("Unexpected required command: setAttributeChangeExtent"); }
        @Override public void changeRectangleAttributes(int p0, int p1, int p2, int p3, int p4, int p5) { throw new AssertionError("Unexpected required command: changeRectangleAttributes"); }
        @Override public void reverseRectangleAttributes(int p0, int p1, int p2, int p3, int p4) { throw new AssertionError("Unexpected required command: reverseRectangleAttributes"); }
        @Override public void insertColumns(int p0) { throw new AssertionError("Unexpected required command: insertColumns"); }
        @Override public void deleteColumns(int p0) { throw new AssertionError("Unexpected required command: deleteColumns"); }
        @Override public void insertLines(int p0) { throw new AssertionError("Unexpected required command: insertLines"); }
        @Override public void deleteLines(int p0) { throw new AssertionError("Unexpected required command: deleteLines"); }
        @Override public void insertCharacters(int p0) { throw new AssertionError("Unexpected required command: insertCharacters"); }
        @Override public void deleteCharacters(int p0) { throw new AssertionError("Unexpected required command: deleteCharacters"); }
        @Override public void eraseCharacters(int p0) { throw new AssertionError("Unexpected required command: eraseCharacters"); }
        @Override public void scrollUp(int p0) { throw new AssertionError("Unexpected required command: scrollUp"); }
        @Override public void scrollDown(int p0) { throw new AssertionError("Unexpected required command: scrollDown"); }
        @Override public void setTabStop() { throw new AssertionError("Unexpected required command: setTabStop"); }
        @Override public void clearTabStop() { throw new AssertionError("Unexpected required command: clearTabStop"); }
        @Override public void clearAllTabStops() { throw new AssertionError("Unexpected required command: clearAllTabStops"); }
        @Override public void setAnsiMode(int p0, boolean p1) { throw new AssertionError("Unexpected required command: setAnsiMode"); }
        @Override public void setDecMode(int p0, boolean p1) { throw new AssertionError("Unexpected required command: setDecMode"); }
        @Override public void setKeyModifierOption(int p0, int p1) { throw new AssertionError("Unexpected required command: setKeyModifierOption"); }
        @Override public void resetKeyModifierOption(int p0) { throw new AssertionError("Unexpected required command: resetKeyModifierOption"); }
        @Override public void resetKeyModifierOptions() { throw new AssertionError("Unexpected required command: resetKeyModifierOptions"); }
        @Override public void disableKeyModifierOption(int p0) { throw new AssertionError("Unexpected required command: disableKeyModifierOption"); }
        @Override public void requestKeyModifierOption(int p0) { throw new AssertionError("Unexpected required command: requestKeyModifierOption"); }
        @Override public void requestKeyFormatOption(int p0) { throw new AssertionError("Unexpected required command: requestKeyFormatOption"); }
        @Override public void setKeyFormatOption(int p0, int p1) { throw new AssertionError("Unexpected required command: setKeyFormatOption"); }
        @Override public void resetKeyFormatOption(int p0) { throw new AssertionError("Unexpected required command: resetKeyFormatOption"); }
        @Override public void resetKeyFormatOptions() { throw new AssertionError("Unexpected required command: resetKeyFormatOptions"); }
        @Override public void applyKittyKeyboardFlags(int p0, int p1) { throw new AssertionError("Unexpected required command: applyKittyKeyboardFlags"); }
        @Override public void pushKittyKeyboardFlags(int p0) { throw new AssertionError("Unexpected required command: pushKittyKeyboardFlags"); }
        @Override public void popKittyKeyboardFlags(int p0) { throw new AssertionError("Unexpected required command: popKittyKeyboardFlags"); }
        @Override public void requestDeviceStatusReport(int p0, boolean p1) { throw new AssertionError("Unexpected required command: requestDeviceStatusReport"); }
        @Override public void requestDeviceAttributes(int p0, int p1) { throw new AssertionError("Unexpected required command: requestDeviceAttributes"); }
        @Override public void requestKittyKeyboardFlags() { throw new AssertionError("Unexpected required command: requestKittyKeyboardFlags"); }
        @Override public void requestWindowReport(int p0) { throw new AssertionError("Unexpected required command: requestWindowReport"); }
        @Override public void resizeWindow(int p0, int p1) { throw new AssertionError("Unexpected required command: resizeWindow"); }
        @Override public void moveWindow(int p0, int p1) { throw new AssertionError("Unexpected required command: moveWindow"); }
        @Override public void minimizeWindow() { throw new AssertionError("Unexpected required command: minimizeWindow"); }
        @Override public void deminimizeWindow() { throw new AssertionError("Unexpected required command: deminimizeWindow"); }
        @Override public void raiseWindow() { throw new AssertionError("Unexpected required command: raiseWindow"); }
        @Override public void lowerWindow() { throw new AssertionError("Unexpected required command: lowerWindow"); }
        @Override public void setMaximized(boolean p0) { throw new AssertionError("Unexpected required command: setMaximized"); }
        @Override public void pushTitleStack(int p0) { throw new AssertionError("Unexpected required command: pushTitleStack"); }
        @Override public void popTitleStack(int p0) { throw new AssertionError("Unexpected required command: popTitleStack"); }
        @Override public void resetAttributes() { throw new AssertionError("Unexpected required command: resetAttributes"); }
        @Override public void setBold(boolean p0) { throw new AssertionError("Unexpected required command: setBold"); }
        @Override public void setFaint(boolean p0) { throw new AssertionError("Unexpected required command: setFaint"); }
        @Override public void setItalic(boolean p0) { throw new AssertionError("Unexpected required command: setItalic"); }
        @Override public void setUnderlineStyle(int p0) { throw new AssertionError("Unexpected required command: setUnderlineStyle"); }
        @Override public void setBlink(boolean p0) { throw new AssertionError("Unexpected required command: setBlink"); }
        @Override public void setInverse(boolean p0) { throw new AssertionError("Unexpected required command: setInverse"); }
        @Override public void setConceal(boolean p0) { throw new AssertionError("Unexpected required command: setConceal"); }
        @Override public void setStrikethrough(boolean p0) { throw new AssertionError("Unexpected required command: setStrikethrough"); }
        @Override public void setOverline(boolean p0) { throw new AssertionError("Unexpected required command: setOverline"); }
        @Override public void setSelectiveEraseProtection(boolean p0) { throw new AssertionError("Unexpected required command: setSelectiveEraseProtection"); }
        @Override public void setForegroundDefault() { throw new AssertionError("Unexpected required command: setForegroundDefault"); }
        @Override public void setBackgroundDefault() { throw new AssertionError("Unexpected required command: setBackgroundDefault"); }
        @Override public void setUnderlineColorDefault() { throw new AssertionError("Unexpected required command: setUnderlineColorDefault"); }
        @Override public void setForegroundIndexed(int p0) { throw new AssertionError("Unexpected required command: setForegroundIndexed"); }
        @Override public void setBackgroundIndexed(int p0) { throw new AssertionError("Unexpected required command: setBackgroundIndexed"); }
        @Override public void setUnderlineColorIndexed(int p0) { throw new AssertionError("Unexpected required command: setUnderlineColorIndexed"); }
        @Override public void setForegroundRgb(int p0, int p1, int p2) { throw new AssertionError("Unexpected required command: setForegroundRgb"); }
        @Override public void setBackgroundRgb(int p0, int p1, int p2) { throw new AssertionError("Unexpected required command: setBackgroundRgb"); }
        @Override public void setUnderlineColorRgb(int p0, int p1, int p2) { throw new AssertionError("Unexpected required command: setUnderlineColorRgb"); }
        @Override public void setWindowTitle(java.lang.String p0) { throw new AssertionError("Unexpected required command: setWindowTitle"); }
        @Override public void setIconTitle(java.lang.String p0) { throw new AssertionError("Unexpected required command: setIconTitle"); }
        @Override public void setIconAndWindowTitle(java.lang.String p0) { throw new AssertionError("Unexpected required command: setIconAndWindowTitle"); }
        @Override public void setCurrentWorkingDirectoryUri(java.lang.String p0) { throw new AssertionError("Unexpected required command: setCurrentWorkingDirectoryUri"); }
        @Override public void startHyperlink(java.lang.String p0, java.lang.String p1) { throw new AssertionError("Unexpected required command: startHyperlink"); }
        @Override public void endHyperlink() { throw new AssertionError("Unexpected required command: endHyperlink"); }
        @Override public void setPaletteColor(int p0, int p1) { throw new AssertionError("Unexpected required command: setPaletteColor"); }
        @Override public void queryPaletteColor(int p0) { throw new AssertionError("Unexpected required command: queryPaletteColor"); }
        @Override public void setDynamicColor(int p0, int p1) { throw new AssertionError("Unexpected required command: setDynamicColor"); }
        @Override public void queryDynamicColor(int p0) { throw new AssertionError("Unexpected required command: queryDynamicColor"); }
        @Override public void queryStatusString(java.lang.String p0) { throw new AssertionError("Unexpected required command: queryStatusString"); }
        @Override public void queryTerminfo(java.lang.String p0) { throw new AssertionError("Unexpected required command: queryTerminfo"); }
        @Override public void shellIntegrationMarker(io.github.ketraterm.protocol.ShellIntegrationEvent p0) { throw new AssertionError("Unexpected required command: shellIntegrationMarker"); }
        @Override public void showNotification(java.lang.String p0, java.lang.String p1, io.github.ketraterm.protocol.NotificationLevel p2) { text.append(p1); }
    }
    private static final class Events implements HostEventSink {
        String title = "";
        @Override public void bell() {}
        @Override public void iconTitleChanged(String value) {}
        @Override public void windowTitleChanged(String value) { title = value; }
        @Override public void resizeWindow(int rows, int columns) {}
    }
    private static final class PlainLine implements TerminalLine {
        @Override public int getWidth() { return 1; }
        @Override public int getCodepoint(int column) { return column == 0 ? 'J' : 0; }
    }
    public static void verify() {
        var sink = new Sink();
        var parser = TerminalParsers.create(sink);
        byte[] bytes = "Java\007\033]52;c;?\007".getBytes(StandardCharsets.UTF_8);
        parser.accept(bytes, 0, bytes.length);
        parser.endOfInput();
        if (!sink.text.toString().equals("Java") || sink.bells != 1) throw new AssertionError(sink.text);
        // OSC 52 is an optional hook; this old implementation intentionally inherits its default.
        sink.requestClipboard("c", "?");
        var events = new Events();
        var buffer = TerminalBuffers.create(8, 2, 0);
        var hostParser = TerminalParsers.create(new HostCommandAdapter(buffer, events, new io.github.ketraterm.host.HostPolicy(), 0, 0, false));
        bytes = "\033]2;Java title\007".getBytes(StandardCharsets.UTF_8);
        hostParser.accept(bytes, 0, bytes.length);
        if (!events.title.equals("Java title")) throw new AssertionError(events.title);
        events.currentWorkingDirectoryChanged("file:///tmp");
        var line = new PlainLine();
        if (line.isCluster(0) || line.readCluster(0, new int[0]) != 0 || line.getCodepoint(0) != 'J') throw new AssertionError("Line defaults");
    }
}

