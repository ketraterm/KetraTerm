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
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalGradleTask
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path

/**
 * Loads a bounded Gradle-task snapshot from IntelliJ's imported external-system model.
 *
 * The loader never invokes Gradle. It reads the public IntelliJ External System
 * model built by a successful Gradle import.
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
            val retained = BoundedSnapshotCollector(MAX_RETAINED_TASKS, TASK_ORDER)
            var visited = 0
            for (projectInfo in ProjectDataManager.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID)) {
                val projectStructure = projectInfo.externalProjectStructure ?: continue
                for (taskNode in ExternalSystemApiUtil.findAllRecursively(projectStructure, ProjectKeys.TASK)) {
                    if (visited++ >= MAX_VISITED_TASKS) break
                    val moduleData = ExternalSystemApiUtil.findParent(taskNode, ProjectKeys.MODULE)?.data ?: continue
                    val entry = taskNode.data.toCompletionTask(workingDirectory, moduleData) ?: continue
                    retained.add(entry)
                }
                if (visited >= MAX_VISITED_TASKS) break
            }
            retained.toSortedList()
        }
    }

    private fun TaskData.toCompletionTask(
        workingDirectory: Path,
        moduleData: ModuleData,
    ): TerminalGradleTask? {
        val taskPath = fullyQualifiedTaskPath(moduleData.id, name) ?: return null
        val linkedProjectDirectory = runCatching { Path.of(linkedExternalProjectPath) }.getOrNull() ?: return null
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
        return toRelativeCompletionPath(workingDirectory, projectDirectory).ifBlank { "." }
    }

    private fun fullyQualifiedTaskPath(
        moduleId: String,
        taskName: String,
    ): String? =
        IntellijGradleTaskPath.fullyQualified(moduleId, taskName)

    private companion object {
        private const val MAX_VISITED_TASKS = 8_192
        private const val MAX_RETAINED_TASKS = 4_096
        private val TASK_ORDER =
            compareBy<TerminalGradleTask, String>(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.path }
                .thenBy { it.projectDirectory }
    }
}

/** Converts public External System module and task data into a Gradle invocation path. */
internal object IntellijGradleTaskPath {
    /** Returns a canonical Gradle task path, or `null` when the imported data is unusable. */
    fun fullyQualified(
        moduleId: String,
        taskName: String,
    ): String? {
        val normalizedTaskName = taskName.trim()
        if (normalizedTaskName.isBlank()) return null
        if (normalizedTaskName.startsWith(':')) return normalizedTaskName.takeIf { it.length > 1 }

        val projectPath = projectPath(moduleId) ?: return null
        return if (projectPath == ":") ":$normalizedTaskName" else "$projectPath:$normalizedTaskName"
    }

    private fun projectPath(moduleId: String): String? {
        val normalizedModuleId = moduleId.trim()
        if (normalizedModuleId.isBlank()) return null
        val segments = normalizedModuleId.split(':').filter(String::isNotBlank)
        val projectSegments = if (normalizedModuleId.startsWith(':')) segments else segments.drop(1)
        if (projectSegments.isEmpty()) return ":"
        return ":" + projectSegments.joinToString(":")
    }
}

/** Adds imported Gradle tasks without leaking IntelliJ external-system APIs into the shared completion module. */
internal class IntellijGradleTaskProviderFactory(
    private val loader: (String?) -> List<TerminalGradleTask>,
) : IntellijCompletionProviderFactory {
    override fun create(context: IntellijCompletionProviderContext): IntellijCompletionProviderRegistration =
        context.createSnapshotRegistration(PRIORITY, loader) { valuesProvider ->
            TerminalCompletionSources.gradleTask(
                sourceId = SOURCE_ID,
                tasksProvider = valuesProvider,
                commandSpecs = context.commandSpecs,
            )
        }

    private companion object {
        private const val PRIORITY = 15
        private const val SOURCE_ID = "intellij-gradle-task"
    }
}
