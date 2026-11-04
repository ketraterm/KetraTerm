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

import io.github.ketraterm.completion.api.*;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

public final class JavaConsumer {
    public static Flow<List<TerminalCompletionCandidate>> stream() {
        var candidate = new TerminalCompletionCandidate("hello", 0, 2, "fixture", TerminalCompletionCandidateKind.COMMAND);
        TerminalCompletionSource source = (request, context, limit, continuation) -> List.of(candidate);
        var engine = TerminalCompletionEngines.fromSources(List.of(new TerminalCompletionSourceEntry(source)));
        return engine.completions(new TerminalCompletionRequest("he", 2));
    }

    public static void verify() throws Exception {
        var option = new io.github.ketraterm.completion.model.TerminalOptionSpec(
                List.of("-m"), "", true, io.github.ketraterm.completion.model.TerminalPathArgumentKind.NONE,
                List.of(), io.github.ketraterm.completion.model.TerminalCompletionValueDomain.NONE,
                io.github.ketraterm.completion.model.TerminalHiddenPathPolicy.DEFAULT, List.of(), "message");
        if (!"message".equals(option.getValueName())) throw new AssertionError("Missing option value name");
        TerminalCompletionShellProvider provider = (request, context, limit, continuation) ->
                new TerminalCompletionShellResult(List.of(), "git.exe");
        var request = new TerminalCompletionRequest("git --v", 7);
        var reply = (TerminalCompletionShellResult) provider.complete(request, TerminalCompletionContext.resolve(request), 10, null);
        if (!"git.exe".equals(reply.getExternalCommandName()) || !reply.getCandidates().isEmpty()) {
            throw new AssertionError("Shell answer identity was lost");
        }
        try (var metadata = TerminalCompletionEngines.class.getResourceAsStream("/META-INF/io.github.ketraterm_ketraterm-completion.kotlin_module")) {
            if (metadata == null || metadata.readAllBytes().length == 0) throw new AssertionError("Missing Kotlin metadata");
        }
    }
}
