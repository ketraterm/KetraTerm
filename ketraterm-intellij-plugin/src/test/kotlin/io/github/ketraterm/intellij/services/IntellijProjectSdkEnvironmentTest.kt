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

import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import io.github.ketraterm.workspace.TerminalShellEnvironment
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/** Launch customization tests independent of IntelliJ services and a running shell. */
class IntellijProjectSdkEnvironmentTest {
    @Test
    fun `enabled project JDK configures forced Java home and path prefix`() {
        val binPath = Path.of("project-jdk", "bin")

        val prepared = profile().withProjectSdkEnvironment(binPath, enabled = true)

        assertEquals(
            TerminalShellEnvironment(
                variables = mapOf("JAVA_HOME" to binPath.parent.toString()),
                pathPrefix = binPath.toString(),
            ),
            prepared.shellEnvironment,
        )
    }

    @Test
    fun `disabled customization leaves the full original profile untouched`() {
        val source =
            profile(mapOf("JAVA_HOME" to "custom-jdk", "PATH" to "custom-bin"))
                .copy(shellEnvironment = TerminalShellEnvironment(mapOf("OTHER" to "value"), "existing-prefix"))

        assertSame(source, source.withProjectSdkEnvironment(Path.of("project-jdk", "bin"), enabled = false))
    }

    @Test
    fun `missing project JDK leaves the profile unchanged`() {
        val source = profile(mapOf("JAVA_HOME" to "custom-jdk"))

        assertSame(source, source.withProjectSdkEnvironment(binPath = null, enabled = true))
    }

    @Test
    fun `a bin path without a parent cannot provide Java home`() {
        val source = profile()

        assertSame(source, source.withProjectSdkEnvironment(Path.of("bin"), enabled = true))
    }

    @Test
    fun `explicit Java and path settings do not disable enabled project customization`() {
        val binPath = Path.of("project-jdk", "bin")
        for (javaHome in listOf("custom-jdk", "")) {
            for (path in listOf("custom-bin", "")) {
                val explicit = mapOf("JAVA_HOME" to javaHome, "PATH" to path)

                val prepared = profile(explicit).withProjectSdkEnvironment(binPath, enabled = true)

                assertEquals(explicit, prepared.environment)
                assertEquals(binPath.parent.toString(), prepared.shellEnvironment.variables["JAVA_HOME"])
                assertEquals(binPath.toString(), prepared.shellEnvironment.pathPrefix)
            }
        }
    }

    @Test
    fun `existing shell Java configuration is replaced while other forced variables survive`() {
        val binPath = Path.of("project-jdk", "bin")
        val source =
            profile().copy(
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "old-jdk", "OTHER" to "value"), "old-bin"),
            )

        val prepared = source.withProjectSdkEnvironment(binPath, enabled = true)

        assertEquals(mapOf("JAVA_HOME" to binPath.parent.toString(), "OTHER" to "value"), prepared.shellEnvironment.variables)
        assertEquals(binPath.toString(), prepared.shellEnvironment.pathPrefix)
        assertEquals("old-jdk", source.shellEnvironment.variables["JAVA_HOME"])
        assertEquals("old-bin", source.shellEnvironment.pathPrefix)
    }

    @Test
    fun `separate launches use the SDK supplied for that launch`() {
        val source = profile()
        val firstBin = Path.of("jdk-21", "bin")
        val nextBin = Path.of("jdk-25", "bin")

        val first = source.withProjectSdkEnvironment(firstBin, enabled = true)
        val next = source.withProjectSdkEnvironment(nextBin, enabled = true)

        assertEquals(firstBin.parent.toString(), first.shellEnvironment.variables["JAVA_HOME"])
        assertEquals(nextBin.parent.toString(), next.shellEnvironment.variables["JAVA_HOME"])
        assertEquals(firstBin.toString(), first.shellEnvironment.pathPrefix)
        assertEquals(nextBin.toString(), next.shellEnvironment.pathPrefix)
        assertEquals(TerminalShellEnvironment.Empty, source.shellEnvironment)
    }

    @Test
    fun `spaces and shell syntax remain literal in the environment payload`() {
        val binPath = Path.of("Java JDK \$literal", "bin")

        val prepared = profile().withProjectSdkEnvironment(binPath, enabled = true)

        assertEquals(binPath.parent.toString(), prepared.shellEnvironment.variables["JAVA_HOME"])
        assertEquals(binPath.toString(), prepared.shellEnvironment.pathPrefix)
    }

    @Test
    fun `preparation preserves command working directory identity and explicit environment`() {
        val explicit = linkedMapOf("CUSTOM" to "value")
        val forced = linkedMapOf("OTHER" to "value")
        val source =
            profile(explicit).copy(
                workingDirectory = Path.of("project"),
                shellEnvironment = TerminalShellEnvironment(forced),
            )

        val prepared = source.withProjectSdkEnvironment(Path.of("jdk", "bin"), enabled = true)

        assertEquals(source.copy(shellEnvironment = prepared.shellEnvironment), prepared)
        assertSame(explicit, prepared.environment)
        assertEquals(mapOf("CUSTOM" to "value"), explicit)
        assertEquals(mapOf("OTHER" to "value"), forced)
        forced["LATER"] = "change"
        assertFalse(prepared.shellEnvironment.variables.containsKey("LATER"))
    }

    @Test
    fun `WSL and Ubuntu profiles do not receive host JDK paths`() {
        for (kind in listOf(TerminalProfileKind.WSL, TerminalProfileKind.UBUNTU)) {
            val source = profile().copy(kind = kind)

            assertSame(source, source.withProjectSdkEnvironment(Path.of("jdk", "bin"), enabled = true))
        }
    }

    @Test
    fun `recognized WSL launchers do not receive a host JDK through a generic profile kind`() {
        val commands =
            listOf(
                listOf("C:\\Windows\\System32\\wsl.exe", "-d", "Debian"),
                listOf("wsl", "--exec", "bash"),
                listOf("ubuntu.exe"),
            )
        for (command in commands) {
            val source = profile().copy(command = command, kind = TerminalProfileKind.DEFAULT)

            assertSame(source, source.withProjectSdkEnvironment(Path.of("jdk", "bin"), enabled = true))
        }
    }

    @Test
    fun `direct SSH is a local process eligible for the initial host environment`() {
        for (executable in listOf("ssh", "/usr/bin/ssh", "C:\\Windows\\System32\\OpenSSH\\SSH.EXE")) {
            val source = profile().copy(command = listOf(executable, "host.example"))
            val binPath = Path.of("jdk", "bin")

            assertEquals(binPath.toString(), source.withProjectSdkEnvironment(binPath, enabled = true).shellEnvironment.pathPrefix)
        }
    }

    @Test
    fun `WSL SDK network paths do not enter local shell environments`() {
        val source = profile()
        for (guestShare in listOf("\\\\wsl$\\Ubuntu", "\\\\WSL.LOCALHOST\\Ubuntu")) {
            val binPath = Path.of(guestShare, "usr", "lib", "jdk", "bin")

            assertSame(source, source.withProjectSdkEnvironment(binPath, enabled = true))
        }
    }

    @Test
    fun `ordinary network SDK paths remain eligible on the local host`() {
        val binPath = Path.of("\\\\sdk-server\\jdks", "21", "bin")

        val prepared = profile().withProjectSdkEnvironment(binPath, enabled = true)

        assertEquals(binPath.parent.toString(), prepared.shellEnvironment.variables["JAVA_HOME"])
        assertEquals(binPath.toString(), prepared.shellEnvironment.pathPrefix)
    }

    @Test
    fun `ordinary host shells remain eligible`() {
        for (kind in listOf(
            TerminalProfileKind.POWERSHELL,
            TerminalProfileKind.BASH,
            TerminalProfileKind.GIT_BASH,
            TerminalProfileKind.ZSH,
        )) {
            val source = profile().copy(kind = kind)
            val binPath = Path.of("jdk", "bin")

            assertEquals(binPath.toString(), source.withProjectSdkEnvironment(binPath, enabled = true).shellEnvironment.pathPrefix)
        }
    }

    private fun profile(environment: Map<String, String> = emptyMap()): TerminalProfile =
        TerminalProfile(
            id = "custom",
            displayName = "Custom Shell",
            command = listOf("custom-shell", "--interactive"),
            environment = environment,
        )
}
