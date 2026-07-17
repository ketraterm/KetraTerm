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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain

/** Reads Git tags from the Git4Idea repository snapshot containing a terminal directory. */
internal class IntellijGitTagLoader(
    private val project: Project,
) {
    /**
     * Loads a bounded deterministic Git-tag snapshot without starting a Git process.
     *
     * @param workingDirectoryUri local `file` URI used to select a repository.
     * @return at most 2,048 tag values, or an empty list for unusable project, URI, or repository state.
     */
    fun load(workingDirectoryUri: String?): List<TerminalCompletionDomainValue> =
        loadIntellijGitRepositorySnapshot(project, workingDirectoryUri) { repository, _ ->
            repository.tagHolder.getTags().keys
                .asSequence()
                .map { tag -> TerminalCompletionDomainValue(tag.name, detail = "tag") }
                .sortedWith(TAG_ORDER)
                .take(MAX_TAGS)
                .toList()
        }

    private companion object {
        private const val MAX_TAGS = 2_048
        private val TAG_ORDER =
            compareBy<TerminalCompletionDomainValue, String>(String.CASE_INSENSITIVE_ORDER) { it.value }
                .thenBy { it.value }
    }
}

/** Adds tag values only where Git accepts an arbitrary revision directly. */
internal class IntellijGitTagProviderFactory(
    private val loader: (String?) -> List<TerminalCompletionDomainValue>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration =
        context.createSnapshotRegistration(PRIORITY, loader) { valuesProvider ->
            TerminalCompletionSources.valueDomain(
                domain = TerminalCompletionValueDomain.GIT_BRANCH,
                sourceId = SOURCE_ID,
                valuesProvider = valuesProvider,
                allowedCommandNames = ALLOWED_COMMAND_NAMES,
                commandSpecs = context.commandSpecs,
            )
        }

    private companion object {
        private const val PRIORITY = 140
        private const val SOURCE_ID = "intellij-git-tag"
        private val ALLOWED_COMMAND_NAMES = setOf("checkout", "merge", "rebase")
    }
}
