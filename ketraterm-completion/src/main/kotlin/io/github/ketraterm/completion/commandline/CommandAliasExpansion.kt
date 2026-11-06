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
package io.github.ketraterm.completion.commandline

import io.github.ketraterm.completion.api.TerminalShellSyntax

/** Expands completed command words for analysis; every editable token retains its original offsets. */
internal fun expandCommandAlias(
    commandLine: String,
    line: TerminalCommandLineContext,
    commandIndex: Int,
    aliases: Map<String, String>,
): TerminalCommandLineContext? {
    val command = line.tokens.getOrNull(commandIndex) ?: return line
    if (command.text !in aliases || commandLine.substring(command.startOffset, command.endOffset) != command.text) return line
    var name = command.text
    var prefix = listOf(command)
    val visited = HashSet<String>()
    repeat(16) {
        val definition = aliases[name] ?: return expandedLine(line, commandIndex, prefix)
        if (!visited.add(name) || definition.length > 4096 || !definition.isLiteralAlias()) return null
        val parsed = TerminalCommandLineTokenizer.parse(definition, definition.length, TerminalShellSyntax.POSIX)
        if (parsed.tokens.isEmpty() || parsed.tokens.firstCommandTokenIndex() != 0) return null
        prefix = parsed.tokens.map { it.copy(startOffset = command.startOffset, endOffset = command.endOffset) } + prefix.drop(1)
        if (prefix.size > 64) return null
        val first = parsed.tokens.first()
        // Bash does not expand the alias currently being expanded a second time.
        // Quoting/escaping the replacement command also prevents another alias lookup.
        if (first.text == name || definition.substring(first.startOffset, first.endOffset) != first.text) {
            return expandedLine(line, commandIndex, prefix)
        }
        name = first.text
    }
    return if (name in aliases) null else expandedLine(line, commandIndex, prefix)
}

private fun expandedLine(
    line: TerminalCommandLineContext,
    commandIndex: Int,
    prefix: List<TerminalCommandLineToken>,
): TerminalCommandLineContext =
    line.copy(
        tokens = line.tokens.take(commandIndex) + prefix + line.tokens.drop(commandIndex + 1),
        activeTokenIndex = line.activeTokenIndex + prefix.size - 1,
    )

/** Recognizes literal words only; decoding remains the shared tokenizer's responsibility. */
private fun String.isLiteralAlias(): Boolean {
    // A trailing blank changes alias expansion of subsequent user-supplied words.
    if (isEmpty() || last().isWhitespace()) return false
    var quote = ShellLexicalRules.NO_QUOTE
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char.isISOControl() && char != '\t') return false
        if (ShellLexicalRules.isEscapePair(this, index, quote, TerminalShellSyntax.POSIX)) {
            if (this[index + 1].isISOControl()) return false
            index += 2
            continue
        }
        if (char == quote) {
            quote = ShellLexicalRules.NO_QUOTE
        } else if (quote == ShellLexicalRules.NO_QUOTE && (char == '\'' || char == '"')) {
            quote = char
        } else if (quote != '\'' && (char == '$' || char == '`')) {
            return false
        } else if (quote == ShellLexicalRules.NO_QUOTE && char in "|&;<>()[*?{}~#\\") {
            return false
        }
        index++
    }
    return quote == ShellLexicalRules.NO_QUOTE
}
