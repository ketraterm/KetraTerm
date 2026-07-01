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
package io.github.ketraterm.completion.model

/**
 * Privacy-preserving category for one command-line argument token.
 */
internal enum class TerminalCommandArgumentKind {
    /**
     * Positional value after the recognized executable and subcommand path.
     */
    POSITIONAL,

    /**
     * Value consumed by an option that accepts a separate following token.
     */
    OPTION_VALUE,

    /**
     * Positional value after the shell option terminator `--`.
     */
    OPTION_TERMINATED_POSITIONAL,
}

/**
 * Privacy-preserving structural record for one command-line argument token.
 *
 * The model deliberately does not store the argument text. For option values,
 * [optionName] stores only the normalized option name that consumed the value.
 *
 * @property kind semantic argument category.
 * @property optionName normalized option name for [TerminalCommandArgumentKind.OPTION_VALUE],
 * or `null` for positional argument kinds.
 */
internal data class TerminalCommandArgumentShape
    @JvmOverloads
    constructor(
        val kind: TerminalCommandArgumentKind,
        val optionName: String? = null,
    ) {
        init {
            require(optionName == null || optionName.isNotBlank()) { "optionName must be null or non-blank" }
            require(kind == TerminalCommandArgumentKind.OPTION_VALUE || optionName == null) {
                "optionName is only valid for OPTION_VALUE arguments"
            }
        }
    }

/**
 * Result of classifying a command line against command specifications.
 *
 * [shape] contains the aggregate command family. [arguments] contains one
 * privacy-preserving entry for each classified argument value token without
 * retaining raw argument text.
 *
 * @property shape aggregate command-line shape.
 * @property arguments privacy-preserving argument classifications.
 * @property matchedSpec whether the executable matched a provided command spec.
 */
internal data class TerminalCommandLineClassification(
    val shape: TerminalCommandLineShape,
    val arguments: List<TerminalCommandArgumentShape>,
    val matchedSpec: Boolean,
)
