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

import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.api.TerminalGradleTask
import io.github.ketraterm.completion.host.TerminalLocalFileUriResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path

/**
 * Loads bounded Gradle tasks from IntelliJ's imported external-system model.
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
    suspend fun load(workingDirectoryUri: String?): List<TerminalGradleTask> {
        val cancellationContext = currentCoroutineContext()
        cancellationContext.ensureActive()
        if (project.isDisposed) return emptyList()
        val workingDirectory = TerminalLocalFileUriResolver.resolve(workingDirectoryUri) ?: return emptyList()
        return readAction {
            cancellationContext.ensureActive()
            ProgressManager.checkCanceled()
            if (project.isDisposed) return@readAction emptyList()
            val retained = BoundedSnapshotCollector(MAX_RETAINED_TASKS, TASK_ORDER)
            var visitedTasks = 0
            val checkpoint = {
                cancellationContext.ensureActive()
                ProgressManager.checkCanceled()
            }
            val roots =
                ProjectDataManager
                    .getInstance()
                    .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
                    .asSequence()
                    .mapNotNull { projectInfo -> projectInfo.externalProjectStructure }
                    .map { root -> PendingGradleNode(root, inheritedModuleData = null) }
                    .asIterable()
            visitBoundedDepthFirst(
                roots = roots,
                maxVisited = MAX_VISITED_MODEL_NODES,
                cancellationCheckpoint = checkpoint,
                children = PendingGradleNode::children,
            ) { pendingNode ->
                val node = pendingNode.node
                if (node.key == ProjectKeys.TASK) {
                    visitedTasks++
                    val taskData = node.data as? TaskData
                    val moduleData = pendingNode.moduleData
                    val entry =
                        if (taskData == null || moduleData == null) {
                            null
                        } else {
                            taskData.toCompletionTask(workingDirectory, moduleData)
                        }
                    if (entry != null) retained.add(entry)
                }
                visitedTasks < MAX_VISITED_TASKS
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
    ): String = toRelativeCompletionPath(workingDirectory, projectDirectory).ifBlank { "." }

    private fun fullyQualifiedTaskPath(
        moduleId: String,
        taskName: String,
    ): String? = IntellijGradleTaskPath.fullyQualified(moduleId, taskName)

    private companion object {
        private const val MAX_VISITED_MODEL_NODES = 16_384
        private const val MAX_VISITED_TASKS = 8_192
        private const val MAX_RETAINED_TASKS = 4_096
        private val TASK_ORDER =
            compareBy<TerminalGradleTask, String>(String.CASE_INSENSITIVE_ORDER) { it.path }
                .thenBy { it.path }
                .thenBy { it.projectDirectory }
    }

    private data class PendingGradleNode(
        val node: DataNode<*>,
        private val inheritedModuleData: ModuleData?,
    ) {
        val moduleData: ModuleData? =
            if (node.key == ProjectKeys.MODULE) {
                node.data as? ModuleData
            } else {
                inheritedModuleData
            }

        fun children(): Iterable<PendingGradleNode> =
            node.children
                .asSequence()
                .map { child -> PendingGradleNode(child, moduleData) }
                .asIterable()
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

/** Creates imported-Gradle-task completion without exposing IntelliJ model APIs to the shared engine. */
internal fun intellijGradleTaskCompletionSource(loader: suspend (String?) -> List<TerminalGradleTask>) =
    TerminalCompletionSources.gradleTask(
        sourceId = "intellij-gradle-task",
        tasksProvider = { request -> loader(request.workingDirectoryUri) },
    )
