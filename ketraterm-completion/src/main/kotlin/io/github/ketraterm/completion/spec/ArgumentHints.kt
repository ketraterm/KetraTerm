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
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.completion.model.TerminalPathArgumentKind

/** Formats only declared argument semantics; never parses descriptions or edits a command line. */
internal fun TerminalCommandSpec.argumentHint(): String {
    if (positionalArguments.isNotEmpty()) {
        return positionalArguments.joinToString(" ") { argument ->
            val name = argument.name.ifBlank { argumentLabel(argument.pathKind, argument.valueDomain).ifEmpty { "value" } }
            if (argument.isOptional) "[$name]" else "<$name>"
        }
    }
    val name = argumentLabel(positionalArgumentPathKind, positionalArgumentValueDomain)
    return if (name.isEmpty()) "" else "<$name>"
}

internal fun TerminalOptionSpec.argumentHint(): String {
    if (!requiresValue) return ""
    val name = valueName.ifBlank { argumentLabel(valuePathKind, valueDomain).ifEmpty { "value" } }
    return "<$name>"
}

private fun argumentLabel(
    pathKind: TerminalPathArgumentKind,
    domain: TerminalCompletionValueDomain,
): String =
    when {
        domain != TerminalCompletionValueDomain.NONE -> domain.id.substringAfterLast('.')
        pathKind == TerminalPathArgumentKind.DIRECTORY -> "directory"
        pathKind == TerminalPathArgumentKind.FILE -> "file"
        pathKind == TerminalPathArgumentKind.FILE_OR_DIRECTORY -> "path"
        else -> ""
    }
