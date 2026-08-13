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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.commandline.resolveCompletionContext
import io.github.ketraterm.completion.model.*

internal suspend fun TerminalCompletionSource.complete(request: TerminalCompletionRequest) =
    complete(
        request = request,
        context = request.resolveCompletionContext(TEST_COMMAND_SPECS),
        limit = 256,
    )

private val TEST_COMMAND_SPECS =
    TerminalCommandSpecs.defaults() +
        TerminalCommandSpec(
            name = "tool",
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            positionalArgumentHiddenPathPolicy = TerminalHiddenPathPolicy.INCLUDE,
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "open",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(
                        names = listOf("--cwd"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--config"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        valueHiddenPathPolicy = TerminalHiddenPathPolicy.EXCLUDE,
                    ),
                ),
            positionalArguments =
                listOf(
                    TerminalArgumentSpec(
                        name = "target",
                        pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        hiddenPathPolicy = TerminalHiddenPathPolicy.INCLUDE,
                    ),
                    TerminalArgumentSpec(
                        name = "path",
                        isVariadic = true,
                        pathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                ),
        )
