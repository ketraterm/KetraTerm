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

import io.github.ketraterm.shell.integration.OscShellIntegration;
import io.github.ketraterm.session.TerminalShellIntegrationFactory;
import io.github.ketraterm.transport.TerminalConnector;
import io.github.ketraterm.transport.TerminalConnectorListener;

public final class JavaConsumer {
    public static TerminalShellIntegrationFactory factory() { return OscShellIntegration.INSTANCE; }
    public static final class Connector implements TerminalConnector {
        public TerminalConnectorListener listener;
        public int closes;
        @Override public void start(TerminalConnectorListener listener) { this.listener = listener; }
        @Override public void write(byte[] bytes, int offset, int length) { throw new AssertionError("Unexpected host input"); }
        @Override public void resize(int columns, int rows) { if (columns <= 0 || rows <= 0) throw new AssertionError("Geometry"); }
        @Override public void close() { closes++; }
    }
    public static void verify() {
        if (factory() == null) throw new AssertionError("Exported factory");
    }
}

