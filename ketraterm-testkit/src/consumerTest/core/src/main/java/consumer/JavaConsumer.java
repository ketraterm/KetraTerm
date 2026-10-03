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

import io.github.ketraterm.core.api.TerminalLine;
import io.github.ketraterm.core.api.TerminalReader;
import io.github.ketraterm.render.api.TerminalColorPalette;

public final class JavaConsumer {
    public static final class Line implements TerminalLine {
        private final int value;
        public Line(int value) { this.value = value; }
        @Override public int getWidth() { return value == 0 ? 0 : 1; }
        @Override public int getCodepoint(int column) { return column == 0 ? value : 0; }
    }
    public static final class Reader implements TerminalReader {
        private final TerminalColorPalette palette = new TerminalColorPalette();
        private final TerminalLine line = new Line('J');
        private final TerminalLine empty = new Line(0);
        @Override public TerminalColorPalette getPalette() { return palette; }
        @Override public boolean isAlternateScreenActive() { return false; }
        @Override public int getWidth() { return 1; }
        @Override public int getHeight() { return 1; }
        @Override public String getWindowTitle() { return "Java"; }
        @Override public String getIconTitle() { return ""; }
        @Override public int getCursorCol() { return 0; }
        @Override public int getCursorRow() { return 0; }
        @Override public int getHistorySize() { return 0; }
        @Override public TerminalLine getLine(int row) { return row == 0 ? line : empty; }
        @Override public int getCodepointAt(int column, int row) { return row == 0 ? line.getCodepoint(column) : 0; }
    }
    public static void verify() {
        TerminalReader reader = new Reader();
        if (reader.getCodepointAt(0, 0) != 'J' || reader.getCodepointAt(Integer.MAX_VALUE, 0) != 0
                || reader.getLine(-1).getWidth() != 0 || reader.getLine(0).isCluster(0)
                || reader.getLine(0).readCluster(0, new int[0]) != 0) throw new AssertionError("Independent core reader");
        if (reader.getPalette() != reader.getPalette()) throw new AssertionError("Retained immutable palette");
    }
}

