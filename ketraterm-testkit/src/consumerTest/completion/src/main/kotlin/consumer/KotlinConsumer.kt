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
package consumer

import io.github.ketraterm.completion.api.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

fun main() =
    runBlocking {
        JavaConsumer.verify()
        check(
            JavaConsumer
                .stream()
                .last()
                .single()
                .replacementText == "hello",
        )
        val nativeItem = Any()
        val source =
            TerminalCompletionSource { _, _, _ ->
                listOf(
                    TerminalCompletionCandidate(
                        "help",
                        0,
                        2,
                        "fixture",
                        TerminalCompletionCandidateKind.COMMAND,
                        feedbackToken = nativeItem,
                    ),
                )
            }
        val engine = TerminalCompletionEngines.fromSources(listOf(TerminalCompletionSourceEntry(source)))
        val result = engine.completions(TerminalCompletionRequest("he", 2)).last().single()
        check(result.replacementText == "help" && result.feedbackToken === nativeItem)
        check(result.copy(score = result.score + 1).feedbackToken === nativeItem)
    }
