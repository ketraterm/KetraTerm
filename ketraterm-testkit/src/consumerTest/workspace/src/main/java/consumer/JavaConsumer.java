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

import io.github.ketraterm.workspace.TerminalProfile;
import io.github.ketraterm.workspace.TerminalProfileKind;
import io.github.ketraterm.workspace.TerminalShellEnvironment;
import java.util.List;
import java.util.Map;

public final class JavaConsumer {
    public static void verify() {
        var environment = new TerminalShellEnvironment(Map.of("TERM", "xterm-256color"), null);
        var profile = new TerminalProfile("custom", "Custom", List.of("shell"), Map.of(), null, TerminalProfileKind.DEFAULT, environment, null);
        if (!profile.getCommand().equals(List.of("shell")) || !profile.getShellEnvironment().getVariables().get("TERM").equals("xterm-256color")) throw new AssertionError("Launch profile");
    }
}

