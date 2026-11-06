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
package io.github.ketraterm.session

import java.util.*

/** Whether an installed inventory producer has verified commands, is discovering, or is unavailable. */
public enum class TerminalShellCommandInventoryStatus { PENDING, READY, UNAVAILABLE }

/**
 * Detached, immutable command availability from one shell environment.
 * READY carries verified commands; additional independent sources may still be discovering.
 * Only [TerminalShellCommandInventoryStatus.READY] carries commands. Other states
 * offer no verified candidates and must not enable unverified catalog fallback.
 * Construction validates aggregate retained text, shares identical insertion/name
 * strings, and builds the sorted prefix index and alias lookup once.
 *
 * [commands] is borrowed only during construction. Duplicate names are rejected.
 * Records must be commands with empty descriptions, nonempty control-free names
 * and insertions, and bounded literal alias definitions. See [MAX_COMMANDS] and
 * [MAX_TEXT_LENGTH] for aggregate bounds; invalid input throws [IllegalArgumentException].
 * This snapshot carries no editing authority and does not execute alias definitions.
 */
public class TerminalShellCommandInventory(
    commands: List<TerminalShellCompletionCandidate>,
    public val status: TerminalShellCommandInventoryStatus = TerminalShellCommandInventoryStatus.READY,
) {
    /** Commands in case-sensitive name order; immutable and independent of the supplied list. */
    public val commands: List<TerminalShellCompletionCandidate>

    /** Literal alias definitions, sharing the snapshot's strings; never evaluated. */
    public val aliases: Map<String, String>

    init {
        require(commands.size <= MAX_COMMANDS)
        require(status == TerminalShellCommandInventoryStatus.READY || commands.isEmpty())
        var textLength = 0
        val owned = ArrayList<TerminalShellCompletionCandidate>(commands.size)
        for (candidate in commands) {
            val name = candidate.displayText
            val replacement = candidate.replacementText
            val alias = candidate.aliasExpansion
            require(candidate.kind == TerminalShellCompletionKind.COMMAND && candidate.description.isEmpty())
            require(name.isNotEmpty() && name.length <= 512 && name.none(Char::isISOControl))
            require(replacement.isNotEmpty() && replacement.length <= 4096 && replacement.none(Char::isISOControl))
            require(alias == null || alias.length <= 4096)
            val sameText = replacement == name
            textLength += name.length + (if (sameText) 0 else replacement.length) + (alias?.length ?: 0)
            require(textLength <= MAX_TEXT_LENGTH)
            // Identity avoids copying candidates whose name and insertion already share the same string.
            @Suppress("StringReferentialEquality")
            owned += if (sameText && replacement !== name) candidate.copy(replacementText = name) else candidate
        }
        owned.sortBy { it.displayText }
        val aliasMap = LinkedHashMap<String, String>()
        var previous: String? = null
        for ((_, displayText, _, _, aliasExpansion) in owned) {
            require(previous != displayText) { "Duplicate shell command name" }
            previous = displayText
            aliasExpansion?.let { aliasMap[displayText] = it }
        }
        this.commands = Collections.unmodifiableList(owned)
        aliases = Collections.unmodifiableMap(aliasMap)
    }

    /**
     * Returns at most [limit] case-sensitive prefix matches in name order.
     * Uses binary search and a read-only view; it does not scan or copy the full inventory.
     */
    public fun matchingCommands(
        prefix: String,
        limit: Int,
    ): List<TerminalShellCompletionCandidate> {
        require(limit > 0)
        var low = 0
        var high = commands.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (commands[middle].displayText < prefix) low = middle + 1 else high = middle
        }
        var end = low
        while (end < commands.size && end - low < limit && commands[end].displayText.startsWith(prefix)) end++
        return if (end == low) emptyList() else commands.subList(low, end)
    }

    public companion object {
        /** Aggregate entry budget, independent of transport-message and popup limits. */
        public const val MAX_COMMANDS: Int = 32_768

        /** Aggregate UTF-16 units retained in unique name/insertion fields and alias definitions. */
        public const val MAX_TEXT_LENGTH: Int = 2_097_152
    }
}
