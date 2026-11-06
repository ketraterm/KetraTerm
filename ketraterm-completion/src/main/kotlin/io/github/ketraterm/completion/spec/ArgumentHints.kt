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
package io.github.ketraterm.completion.spec

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** Formats declared operands only; scalar routing metadata does not specify arity or requiredness. */
internal fun TerminalCommandSpec.argumentHint(): String =
    positionalArguments.joinToString(" ") { argument ->
        val name = argument.name.ifBlank { argumentLabel(argument.pathKind).ifEmpty { "value" } }
        if (argument.isOptional) "[$name]" else "<$name>"
    }

internal fun TerminalOptionSpec.argumentHint(): String {
    if (!requiresValue) return ""
    val name = valueName.ifBlank { argumentLabel(valuePathKind).ifEmpty { "value" } }
    return "<$name>"
}

private fun argumentLabel(pathKind: TerminalPathArgumentKind): String =
    when (pathKind) {
        TerminalPathArgumentKind.DIRECTORY -> "directory"
        TerminalPathArgumentKind.FILE -> "file"
        TerminalPathArgumentKind.FILE_OR_DIRECTORY -> "path"
        TerminalPathArgumentKind.NONE -> ""
    }
