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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore;
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator;
import java.nio.file.Path;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;

public final class JavaConsumer {
    public static TerminalCompletionLearningCoordinator createCoordinator(
            TerminalCompletionLearningStore store, CoroutineScope scope, Path path, boolean enabled) {
        return new TerminalCompletionLearningCoordinator(store, scope, path, enabled, Dispatchers.getIO(), failure -> {
            throw new AssertionError(failure);
        });
    }

    public static void verify() {
        if (!TerminalCompletionLearningCoordinator.currentFileName().endsWith(".tsv")) {
            throw new AssertionError("Versioned snapshot filename");
        }
        if (!new TerminalCompletionLearningStore().snapshot().getRankingStats().isEmpty()) {
            throw new AssertionError("Empty learning store");
        }
    }
}
