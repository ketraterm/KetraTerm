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

import io.github.ketraterm.render.api.*;
import io.github.ketraterm.render.cache.TerminalRenderPublisher;
import io.github.ketraterm.render.cache.TerminalRenderCache;
import java.util.function.Consumer;
import kotlin.Unit;

public final class JavaConsumer {
    public static void read(TerminalRenderPublisher publisher, Consumer<TerminalRenderCache> callback) {
        publisher.readCurrent(cache -> { callback.accept(cache); return Unit.INSTANCE; });
    }
    public static final class Frame implements TerminalRenderFrame, TerminalRenderFrameReader {
        private final int value;
        public Frame(int value) { this.value = value; }
        @Override public int getColumns() { return 1; }
        @Override public int getRows() { return 1; }
        @Override public long getFrameGeneration() { return value; }
        @Override public long getStructureGeneration() { return 1; }
        @Override public TerminalRenderBufferKind getActiveBuffer() { return TerminalRenderBufferKind.PRIMARY; }
        @Override public TerminalRenderCursor getCursor() {
            return new TerminalRenderCursor(0, 0, true, false, TerminalRenderCursorShape.BLOCK, value);
        }
        @Override public long lineGeneration(int row) { return value; }
        @Override public boolean lineWrapped(int row) { return false; }
        @Override public void readRenderFrame(TerminalRenderFrameConsumer consumer) { consumer.accept(this); }
        @Override public void copyLine(int row, int[] codes, int codeOffset, long[] attrs, int attrOffset,
                int[] flags, int flagOffset, long[] extra, int extraOffset, int[] links, int linkOffset,
                TerminalRenderClusterSink text, TerminalRenderClusterDataSink data) {
            codes[codeOffset] = value;
            attrs[attrOffset] = 0;
            flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT;
            if (extra != null) extra[extraOffset] = 0;
            if (links != null) links[linkOffset] = 0;
        }
    }

    public static void verify() {
        var frame = new Frame('J');
        if (frame.getContentGeneration() != 'J' || frame.getHistoryContentGeneration() != 'J'
                || frame.getHistorySize() != 0 || frame.getScrollbackOffset() != 0
                || frame.getOutputEndAbsoluteRow() != Long.MAX_VALUE || frame.lineId(0) != 0) {
            throw new AssertionError("Conservative inherited frame defaults");
        }
        frame.readRenderFrame(100, Integer.MAX_VALUE, resolved -> {
            if (resolved.getRows() != 1 || resolved.getScrollbackOffset() != 0) throw new AssertionError("Unsupported viewport fallback");
        });
        frame.copyCursor((column, row, visible, blinking, shape, generation) -> {
            if (column != 0 || row != 0 || !visible || blinking || generation != 'J') throw new AssertionError("Cursor default");
        });
        var publisher = new TerminalRenderPublisher(1, 1);
        publisher.updateAndPublish(frame);
        int code = publisher.readCurrent(cache -> cache.getCodeWords()[0]);
        if (code != 'J') throw new AssertionError("Java callback lease");
    }
}

