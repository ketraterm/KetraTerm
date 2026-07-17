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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.ketraterm.completion.api.TerminalFileEntry
import java.nio.file.Path
import java.util.*

/**
 * Uses IntelliJ's project-aware VFS snapshot for directories in project
 * content, falling back to bounded local scanning outside the project.
 *
 * VFS access and project-content checks run under an IntelliJ read action.
 * Only direct children already represented by the VFS are inspected; this
 * adapter does not refresh the VFS or perform filesystem I/O while in the read
 * action.
 *
 * @property project project whose content index constrains VFS suggestions.
 * @property fallback scanner used for directories outside indexed project content.
 * @property virtualFileResolver resolver for an existing VFS directory snapshot.
 * @property maxVisitedEntries positive cap on inspected VFS children.
 * @property maxMatchingEntries positive cap on retained matching children.
 * @throws IllegalArgumentException if [maxVisitedEntries] or
 * [maxMatchingEntries] is not positive.
 */
internal class IntellijProjectDirectoryScanner(
    private val project: Project,
    private val fallback: IntellijDirectoryScanner = BoundedIntellijDirectoryScanner(),
    private val virtualFileResolver: (Path) -> VirtualFile? = VirtualFileManager.getInstance()::findFileByNioPath,
    private val maxVisitedEntries: Int = DEFAULT_MAX_VISITED_ENTRIES,
    private val maxMatchingEntries: Int = DEFAULT_MAX_MATCHING_ENTRIES,
) : IntellijDirectoryScanner {
    init {
        require(maxVisitedEntries > 0) { "maxVisitedEntries must be > 0, was $maxVisitedEntries" }
        require(maxMatchingEntries > 0) { "maxMatchingEntries must be > 0, was $maxMatchingEntries" }
    }

    /**
     * Reads a bounded project-content snapshot or delegates to [fallback].
     *
     * @param directory normalized absolute local directory.
     * @param entryNamePrefix case-insensitive direct-child name prefix.
     * @return deterministically ordered entries; an empty list for a disposed
     * project or an indexed non-directory; otherwise [fallback]'s result when
     * no applicable project-content snapshot exists.
     */
    override fun scan(
        directory: Path,
        entryNamePrefix: String,
    ): List<TerminalFileEntry> {
        if (project.isDisposed) return emptyList()
        val projectEntries =
            ApplicationManager.getApplication().runReadAction<List<TerminalFileEntry>?> {
                if (project.isDisposed) return@runReadAction emptyList()
                val virtualDirectory = virtualFileResolver(directory) ?: return@runReadAction null
                if (!virtualDirectory.isDirectory) return@runReadAction emptyList()
                val fileIndex = ProjectRootManager.getInstance(project).fileIndex
                if (!fileIndex.isInContent(virtualDirectory)) return@runReadAction null

                val matches = PriorityQueue(maxMatchingEntries, ENTRY_ORDER.reversed())
                val children = virtualDirectory.children
                val limit = minOf(children.size, maxVisitedEntries)
                for (index in 0 until limit) {
                    val child = children[index]
                    if (!fileIndex.isInContent(child) || !child.name.startsWith(
                            entryNamePrefix,
                            ignoreCase = true
                        )
                    ) continue
                    val entry = TerminalFileEntry(child.name, child.isDirectory)
                    if (matches.size < maxMatchingEntries) {
                        matches += entry
                    } else if (ENTRY_ORDER.compare(entry, matches.peek()) < 0) {
                        matches.remove()
                        matches += entry
                    }
                }
                ArrayList(matches).apply { sortWith(ENTRY_ORDER) }
            }
        return projectEntries ?: fallback.scan(directory, entryNamePrefix)
    }

    private companion object {
        private const val DEFAULT_MAX_VISITED_ENTRIES = 8_192
        private const val DEFAULT_MAX_MATCHING_ENTRIES = 256
        private val ENTRY_ORDER =
            compareBy<TerminalFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
    }
}
