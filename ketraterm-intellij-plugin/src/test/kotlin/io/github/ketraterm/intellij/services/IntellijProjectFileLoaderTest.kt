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

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

/** IntelliJ fixture tests for project-file completion through Go to File. */
class IntellijProjectFileLoaderTest : BasePlatformTestCase() {
    /** Verifies that a non-contiguous query uses IntelliJ's project-file search. */
    fun testNonContiguousQueryFindsProjectFile() {
        myFixture.addFileToProject("settings.gradle.kts", "")
        myFixture.addFileToProject("build.gradle.kts", "")
        val entries = loader().load(projectDirectoryUri(), "sgk")

        assertTrue(entries.toString(), entries.any { it.path == "settings.gradle.kts" })
    }

    /** Verifies that IntelliJ applies an explicit fuzzy directory qualifier. */
    fun testDirectoryQualifierScopesMatch() {
        myFixture.addFileToProject("src/main/.generated/Hidden.kt", "")
        myFixture.addFileToProject("other/.generated/Hidden.kt", "")

        val entries = loader().load(projectDirectoryUri(), "src/main/.g")

        assertEquals(
            listOf("src/main/.generated"),
            entries.map { it.path },
        )
        assertTrue(entries.single().isDirectory)
    }

    private fun loader(): IntellijProjectFileLoader {
        val projectDirectory = Path.of(requireNotNull(project.basePath))
        val virtualProjectDirectory = ProjectRootManager.getInstance(project).contentRoots.single()
        return IntellijProjectFileLoader(project) { file ->
            projectDirectory.resolve(file.path.removePrefix("${virtualProjectDirectory.path}/"))
        }
    }

    private fun projectDirectoryUri(): String = Path.of(requireNotNull(project.basePath)).toUri().toString()
}
