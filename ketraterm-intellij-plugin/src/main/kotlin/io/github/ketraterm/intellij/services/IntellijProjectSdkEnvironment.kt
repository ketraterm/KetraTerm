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
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import java.nio.file.Path

/** Resolves the current project JDK once when an eligible local terminal starts. */
internal fun TerminalProfile.withProjectSdkEnvironment(
    project: Project,
    enabled: Boolean,
): TerminalProfile {
    if (!enabled || !supportsLocalProjectSdk()) return this
    return withProjectSdkEnvironment(projectSdkBinPath(project), enabled = true)
}

/**
 * Requests the same JDK environment policy as the IntelliJ reworked terminal:
 * force the project `JAVA_HOME` and prepend its `bin` after shell startup files.
 * The workspace also applies this payload to the initial process environment.
 *
 * Explicit launch environment values remain available as the startup baseline;
 * this enabled customization takes precedence over their Java configuration.
 * Guest SDK paths cannot be applied to the local host process.
 */
internal fun TerminalProfile.withProjectSdkEnvironment(
    binPath: Path?,
    enabled: Boolean,
): TerminalProfile {
    if (!enabled || binPath == null || !supportsLocalProjectSdk()) return this
    val javaHome = binPath.parent ?: return this
    val normalizedPath = binPath.toString().replace('\\', '/')
    if (normalizedPath.startsWith("//wsl$/", ignoreCase = true) ||
        normalizedPath.startsWith("//wsl.localhost/", ignoreCase = true)
    ) {
        return this
    }

    return copy(
        shellEnvironment =
            shellEnvironment.copy(
                variables = shellEnvironment.variables + ("JAVA_HOME" to javaHome.toString()),
                pathPrefix = binPath.toString(),
            ),
    )
}

private fun TerminalProfile.supportsLocalProjectSdk(): Boolean {
    if (kind == TerminalProfileKind.WSL || kind == TerminalProfileKind.UBUNTU) return false
    val commandKind = TerminalProfileKind.classify("", "", command)
    return commandKind != TerminalProfileKind.WSL && commandKind != TerminalProfileKind.UBUNTU
}
