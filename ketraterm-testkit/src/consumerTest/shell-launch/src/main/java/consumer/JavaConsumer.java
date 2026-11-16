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

import io.github.ketraterm.shell.launch.TerminalShellLaunch;
import io.github.ketraterm.workspace.TerminalProfile;
import io.github.ketraterm.workspace.TerminalProfileKind;
import io.github.ketraterm.workspace.TerminalShellEnvironment;
import java.util.List;
import java.util.Map;

public final class JavaConsumer {
    public static void verify() {
        var profile = new TerminalProfile("custom", "Custom", List.of("custom"), Map.of(), null,
                TerminalProfileKind.DEFAULT, TerminalShellEnvironment.Companion.getEmpty(), null);
        try (var launch = TerminalShellLaunch.prepare(profile, false)) {
            if (launch.getPromptMarkersExpected()) throw new AssertionError("Disabled shell hooks");
            if (!launch.getProfile().getCommand().equals(List.of("custom"))) throw new AssertionError("Launch command");
        }
    }
}
