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
import com.intellij.openapi.util.io.FileUtil
import io.github.ketraterm.completion.api.TerminalCompletionSourceEntry
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalGradleTask
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import org.jetbrains.plugins.gradle.util.GradleTaskData
import org.jetbrains.plugins.gradle.util.getGradleTasks
import java.nio.file.Path
import java.util.*

/**
 * Loads a bounded Gradle-task snapshot from IntelliJ's imported external-system model.
 *
 * The loader never invokes Gradle. It reads IntelliJ Gradle's resolved task
 * index, which is built from a successful import and understands modules and
 * composite project task paths.
 */
internal class IntellijGradleTaskLoader(
    private val project: Project,
) {
    /**
     * Loads the imported Gradle tasks visible to a terminal working directory.
     *
     * @param workingDirectoryUri local `file` URI used to relativize `-p` project directories.
     * @return at most 4,096 deterministic task entries, or an empty list when the
     * project is disposed, the directory is unavailable, or no Gradle model is imported.
     */
    fun load(workingDirectoryUri: String?): List<TerminalGradleTask> {
        if (project.isDisposed) return emptyList()
        val workingDirectory = TerminalLocalFileUriResolver.resolve(workingDirectoryUri) ?: return emptyList()
        return ApplicationManager.getApplication().runReadAction<List<TerminalGradleTask>> {
            if (project.isDisposed) return@runReadAction emptyList()
            val retained = PriorityQueue(MAX_RETAINED_TASKS, TASK_ORDER.reversed())
            var visited = 0
            for (tasksByModule in getGradleTasks(project).values) {
                for ((_, tasks) in tasksByModule.entrySet()) {
                    for (task in tasks) {
                        if (visited++ >= MAX_VISITED_TASKS) break
                        val entry = task.toCompletionTask(workingDirectory) ?: continue
                        if (retained.size < MAX_RETAINED_TASKS) {
                            retained += entry
                        } else if (TASK_ORDER.compare(entry, retained.peek()) < 0) {
                            retained.remove()
                            retained += entry
                        }
                    }
                    if (visited >= MAX_VISITED_TASKS) break
                }
                if (visited >= MAX_VISITED_TASKS) break
            }
            ArrayList(retained).apply { sortWith(TASK_ORDER) }
        }
    }

    private fun GradleTaskData.toCompletionTask(
        workingDirectory: Path,
    ): TerminalGradleTask? {
        val taskPath = getFqnTaskName().takeIf { it.startsWith(':') && it.length > 1 } ?: return null
        val linkedProjectDirectory = runCatching { Path.of(data.linkedExternalProjectPath) }.getOrNull() ?: return null
        val projectDirectory = relativeProjectDirectory(workingDirectory, linkedProjectDirectory)
        return TerminalGradleTask(
            path = taskPath,
            description = description.orEmpty(),
            projectDirectory = projectDirectory,
        )
    }

    private fun relativeProjectDirectory(
        workingDirectory: Path,
        projectDirectory: Path,
    ): String {
        val path = runCatching { workingDirectory.relativize(projectDirectory) }.getOrElse { projectDirectory }
        return FileUtil.toSystemIndependentName(path.toString()).removeSuffix("/").ifBlank { "." }
    }

    private companion object {
        private const val MAX_VISITED_TASKS = 8_192
        private const val MAX_RETAINED_TASKS = 4_096
        private val TASK_ORDER =
            compareBy<TerminalGradleTask, String>(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.path }
                .thenBy { it.projectDirectory }
    }
}

/** Adds imported Gradle tasks without leaking IntelliJ external-system APIs into the shared completion module. */
internal class IntellijGradleTaskProviderFactory(
    private val loader: (String?) -> List<TerminalGradleTask>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration {
        val snapshotProvider =
            context.snapshotService.createValueProvider(
                keyProvider = context.workingDirectoryUriProvider,
                loader = loader,
                onSnapshotChanged = context.onSnapshotChanged,
            )
        val source =
            TerminalCompletionSources.gradleTask(
                sourceId = SOURCE_ID,
                tasksProvider = snapshotProvider::values,
                commandSpecs = context.commandSpecs,
            )
        return IntellijCompletionProviderRegistration(
            sourceEntry = TerminalCompletionSourceEntry(source = source, priority = PRIORITY),
            resources = listOf(snapshotProvider),
        )
    }

    private companion object {
        private const val PRIORITY = 150
        private const val SOURCE_ID = "intellij-gradle-task"
    }
}
