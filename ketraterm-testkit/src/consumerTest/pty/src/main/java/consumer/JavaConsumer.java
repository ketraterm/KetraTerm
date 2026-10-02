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

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import io.github.ketraterm.pty.PtyConnector;
import java.io.InputStream;
import java.io.OutputStream;

public final class JavaConsumer {
    public static void verify() throws Exception {
        var process = newProcess();
        try (var connector = new PtyConnector(process, 1024, "consumer-reader", "consumer-watcher")) {
            connector.resize(91, 27);
            if (process.getWinSize().getColumns() != 91 || process.getWinSize().getRows() != 27) {
                throw new AssertionError("Native process dimensions");
            }
        }
        if (process.isAlive()) throw new AssertionError("Connector must destroy its owned process");
    }

    public static PtyProcess newProcess() {
        // A native-process contract implementation, without OS process creation or reader threads.
        return new PtyProcess() {
            private WinSize size = new WinSize(80, 24);
            private boolean alive = true;

            @Override public void setWinSize(WinSize value) { size = value; }
            @Override public WinSize getWinSize() { return size; }
            @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { alive = false; }
            @Override public boolean isAlive() { return alive; }
        };
    }
}
