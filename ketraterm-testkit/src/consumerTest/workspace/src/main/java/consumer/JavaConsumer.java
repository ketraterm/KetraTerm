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
import io.github.ketraterm.workspace.TerminalWorkspace;
import io.github.ketraterm.workspace.TerminalWorkspaceListener;
import io.github.ketraterm.workspace.TerminalWorkspaceOpenOptions;
import java.util.List;
import java.util.Map;

public final class JavaConsumer {
    public static void verify() {
        var profile = new TerminalProfile("bash", "Bash", List.of("bash"), Map.of(), null,
                TerminalProfileKind.BASH, TerminalShellEnvironment.Companion.getEmpty(), null);
        if (!profile.getId().equals("bash")) throw new AssertionError("Profile identity");
        var options = TerminalWorkspaceOpenOptions.create(builder -> builder.setColumns(100));
        if (options.getColumns() != 100) throw new AssertionError("Workspace geometry");
        try (var workspace = new TerminalWorkspace(new TerminalWorkspaceListener() {})) {
            if (!workspace.tabSnapshot().isEmpty()) throw new AssertionError("Empty workspace");
        }
    }
}
