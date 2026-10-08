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
import io.github.ketraterm.host.HostCommandAdapter;
import io.github.ketraterm.host.HostEventSink;
import io.github.ketraterm.parser.api.TerminalParsers;
import io.github.ketraterm.parser.spi.TerminalCommandSink;
import io.github.ketraterm.protocol.NotificationLevel;
import java.nio.charset.StandardCharsets;

public final class JavaConsumer {
    public static void verify() throws Exception {
        var buffer = TerminalBuffers.create(12, 2, 0);
        var adapter = new HostCommandAdapter(buffer);
        TerminalCommandSink sink = adapter;
        sink.writeCodepoint('J');
        var parser = TerminalParsers.create(adapter);
        var bytes = "ava".getBytes(StandardCharsets.UTF_8);
        parser.accept(bytes, 0, bytes.length);
        parser.endOfInput();
        if (!buffer.getLineAsString(0).stripTrailing().equals("Java")) throw new AssertionError("Grid content");
        HostEventSink.NONE.showNotification("title", "body", NotificationLevel.INFO);
        try (var metadata = HostCommandAdapter.class.getResourceAsStream("/META-INF/io.github.ketraterm_ketraterm-host.kotlin_module")) {
            if (metadata == null || metadata.readAllBytes().length == 0) throw new AssertionError("Missing Kotlin metadata");
        }
    }
}
