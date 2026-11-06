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

import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class IntellijGradleTaskLoaderTest {
    private val workspace = Path.of("gradle-completion-test").toAbsolutePath().normalize()

    @Test
    fun `unrelated terminal directories do not receive imported tasks`() =
        runBlocking {
            val root = build(workspace.resolve("project"), "projectTask")
            val loader = loader(root)

            assertTrue(loader.load(workspace.resolve("other").toUri().toString()).isEmpty())
            assertTrue(loader.load(workspace.resolve("project-other").toUri().toString()).isEmpty())
            assertTrue(loader.load(workspace.toUri().toString()).isEmpty())
        }

    @Test
    fun `selects only the deepest imported build independent of model order`() =
        runBlocking {
            val parent = build(workspace.resolve("project"), "parentTask")
            val nested = build(workspace.resolve("project/tools"), "nestedTask")
            val other = build(workspace.resolve("other"), "otherTask")
            for (roots in listOf(listOf(parent, nested, other), listOf(other, nested, parent))) {
                val loader = loader(*roots.toTypedArray())
                assertEquals(
                    listOf(":nestedTask"),
                    loader.load(workspace.resolve("project/tools/src").toUri().toString()).map { it.path },
                )
                assertEquals(
                    listOf(":otherTask"),
                    loader.load(workspace.resolve("other").toUri().toString()).map { it.path },
                )
            }
        }

    @Test
    fun `unrelated models cannot consume the selected build task budget`() =
        runBlocking {
            val unrelated =
                Node(
                    projectDirectory = workspace.resolve("unrelated"),
                    children = Iterable { error("Unrelated task traversal") },
                )
            val selected = build(workspace.resolve("selected"), "selectedTask")

            assertEquals(
                listOf(":selectedTask"),
                loader(unrelated, selected).load(workspace.resolve("selected").toUri().toString()).map { it.path },
            )
        }

    private fun loader(vararg roots: GradleModelNode) = IntellijGradleTaskLoader { collector -> collector(roots.asList()) }

    @Test
    fun `directory overrides select another build using relative absolute and normalized paths`() =
        runBlocking {
            val first = build(workspace.resolve("first"), "firstTask")
            val secondDirectory = workspace.resolve("second project")
            val second = build(secondDirectory, "secondTask")
            val absolute = secondDirectory.toString().replace('\\', '/')
            val source = intellijGradleTaskCompletionSource(loader(first, second)::load)
            val lines =
                listOf(
                    "gradle -p '../second project' se",
                    "gradle --project-dir='../second project/./sub/..' se",
                    "gradle --project-dir '$absolute' se",
                    "gradle -p . --project-dir '../second project' se",
                )
            for (line in lines) {
                assertEquals(line, listOf("secondTask"), replacements(source, workspace.resolve("first"), line))
            }
            assertTrue(replacements(source, workspace.resolve("first"), "gradle -p ../unknown se").isEmpty())
            assertEquals(
                listOf("secondTask"),
                replacements(source, workspace.resolve("unrelated"), "gradle -p '../second project' se"),
            )
        }

    @Test
    fun `subproject directories select short tasks while qualified tasks stay build relative`() =
        runBlocking {
            val directory = workspace.resolve("project")
            val appDirectory = directory.resolve("app")
            val app =
                Node(
                    projectDirectory = appDirectory,
                    moduleId = "root:app",
                    children = listOf(Node(task = GradleTaskReadModel("appTask", "app task", appDirectory.toString()))),
                )
            val source = intellijGradleTaskCompletionSource(loader(build(directory, "rootTask", app))::load)

            assertEquals(listOf("rootTask"), replacements(source, directory, "gradle ro"))
            assertEquals(listOf("appTask"), replacements(source, appDirectory, "gradle ap"))
            assertEquals(listOf("appTask"), replacements(source, appDirectory.resolve("src"), "gradle ap"))
            assertTrue(replacements(source, appDirectory, "gradle ro").isEmpty())
            assertEquals(listOf("appTask"), replacements(source, directory, "gradle -p app ap"))
            assertEquals(listOf(":rootTask"), replacements(source, directory, "gradle -p app :ro"))
            assertEquals(listOf(":app:appTask"), replacements(source, directory, "gradle :app:ap"))
        }

    @Test
    fun `a truncated task model cannot choose an unvisited subproject default`() =
        runBlocking {
            val directory = workspace.resolve("large")
            val appDirectory = directory.resolve("app")
            val root =
                Node(
                    projectDirectory = directory,
                    moduleId = "root",
                    children =
                        List(8_192) { index ->
                            Node(task = GradleTaskReadModel("rootTask$index", "root task", directory.toString()))
                        } + Node(projectDirectory = appDirectory, moduleId = "root:app"),
                )
            val source = intellijGradleTaskCompletionSource(loader(root)::load)

            assertTrue(replacements(source, appDirectory, "gradle root").isEmpty())
            assertTrue(replacements(source, directory, "gradle -p app root").isEmpty())
            assertTrue(replacements(source, appDirectory, "gradle :root").isNotEmpty())
        }

    @Test
    fun `invalid or remote directory metadata cannot select a local build through an override`() =
        runBlocking {
            val loader = IntellijGradleTaskLoader { error("Unexpected local model access") }
            for (uri in listOf(null, "", "ssh://remote/project", "file://remote/project", "not a URI")) {
                assertTrue(loader.load(uri, workspace.toString()).isEmpty())
            }
        }

    private suspend fun replacements(
        source: TerminalCompletionSource,
        directory: Path,
        line: String,
    ): List<String> {
        val request =
            TerminalCompletionRequest(
                commandLine = line,
                cursorOffset = line.length,
                workingDirectoryUri = directory.toUri().toString(),
                shellCapabilities = TerminalShellCapabilities.POSIX,
            )
        return source.complete(request, TerminalCompletionContext.resolve(request), 20).map { it.replacementText }
    }

    private fun build(
        directory: Path,
        task: String,
        vararg modules: GradleModelNode,
    ): GradleModelNode =
        Node(
            projectDirectory = directory,
            moduleId = "root",
            children = listOf(Node(task = GradleTaskReadModel(task, task, directory.toString()))) + modules,
        )

    private data class Node(
        override val projectDirectory: Path? = null,
        override val moduleId: String? = null,
        override val task: GradleTaskReadModel? = null,
        override val children: Iterable<GradleModelNode> = emptyList(),
    ) : GradleModelNode
}
