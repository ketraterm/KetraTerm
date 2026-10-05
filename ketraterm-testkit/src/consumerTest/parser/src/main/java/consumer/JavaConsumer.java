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

import io.github.ketraterm.parser.api.TerminalParsers;
import io.github.ketraterm.parser.spi.TerminalCommandSink;
import io.github.ketraterm.protocol.NotificationLevel;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

public final class JavaConsumer {
    public static TerminalCommandSink sink(StringBuilder output) {
        return (TerminalCommandSink) Proxy.newProxyInstance(
            TerminalCommandSink.class.getClassLoader(),
            new Class<?>[] {TerminalCommandSink.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isAlternateScreenActive": return false;
                    case "writeCodepoint": output.appendCodePoint((Integer) args[0]); return null;
                    case "showNotification":
                        if (args[2] != NotificationLevel.INFO) throw new AssertionError("notification level");
                        output.append(args[1]); return null;
                    default: throw new AssertionError("Unexpected parser callback: " + method.getName());
                }
            });
    }

    public static void verify() throws Exception {
        var output = new StringBuilder();
        var sink = sink(output);
        var parser = TerminalParsers.create(sink);
        var bytes = "Java".getBytes(StandardCharsets.UTF_8);
        parser.accept(bytes, 0, bytes.length);
        parser.endOfInput();
        sink.showNotification("title", "!", NotificationLevel.INFO);
        if (!output.toString().equals("Java!")) throw new AssertionError(output);
        var custom = TerminalParsers.create(sink, () -> 0, (command, payload, offset, length) -> {
            if (command != 1341) throw new AssertionError("Custom command number");
            output.append(new String(payload, offset, length, StandardCharsets.UTF_8));
        });
        var customBytes = "\033]1341;custom\007".getBytes(StandardCharsets.UTF_8);
        custom.accept(customBytes, 0, customBytes.length);
        if (!output.toString().equals("Java!custom")) throw new AssertionError(output);
        var body = "x".repeat(5000);
        var configured = TerminalParsers.create(sink, () -> 0, 5005, (command, payload, offset, length) -> {
            if (!new String(payload, offset, length, StandardCharsets.UTF_8).equals(body)) throw new AssertionError("Custom body");
            output.append('!');
        });
        var configuredBytes = ("\033]1341;" + body + "\007").getBytes(StandardCharsets.UTF_8);
        configured.accept(configuredBytes, 0, configuredBytes.length);
        if (!output.toString().equals("Java!custom!")) throw new AssertionError(output);
        try (var metadata = TerminalParsers.class.getResourceAsStream("/META-INF/io.github.ketraterm_ketraterm-parser.kotlin_module")) {
            if (metadata == null || metadata.readAllBytes().length == 0) throw new AssertionError("Missing Kotlin metadata");
        }
    }
}
