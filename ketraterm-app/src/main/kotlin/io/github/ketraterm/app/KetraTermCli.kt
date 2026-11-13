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
package io.github.ketraterm.app

import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import java.io.IOException
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.*
import java.util.*

/**
 * Installs the standalone companion CLI and supplies its local launch environment.
 *
 * Preparation performs file I/O and propagates installation failures to the app's
 * launch-error handler before advertising the helper directory. WSL profiles are
 * left untouched: native helper and configuration paths are not guest paths.
 * Explicit [TerminalProfile.shellEnvironment] overrides remain authoritative.
 * Default helpers live in a private temporary directory until JVM exit.
 */
internal class KetraTermCli(
    private val configPath: Path,
    directory: Path? = null,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
) {
    private val privateDirectory = directory == null
    private val directory: Path by lazy { directory ?: createPrivateDirectory() }

    fun prepare(
        profile: TerminalProfile,
        inheritedEnvironment: Map<String, String> = System.getenv(),
    ): TerminalProfile {
        if (profile.kind == TerminalProfileKind.WSL || profile.kind == TerminalProfileKind.UBUNTU) return profile

        Files.createDirectories(directory)
        install("ketra", executable = true)
        install("ketra.bat", executable = false)

        val environment = profile.environment.toMutableMap()

        fun setVariable(
            name: String,
            value: String,
        ) {
            val key = inheritedEnvironment.keys.firstOrNull { it.equals(name, ignoreCase = windows) } ?: name
            environment.keys.removeAll { it.equals(name, ignoreCase = windows) }
            environment[key] = value
        }
        setVariable("KetraTerm_VERSION", appVersion)
        setVariable("KetraTerm_CONFIG_PATH", configPath.toAbsolutePath().toString())
        setVariable("KetraTerm_OS", "${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        setVariable("KetraTerm_JVM", "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")

        val profilePath = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }
        val inheritedPath = inheritedEnvironment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }
        val pathKey = inheritedPath?.key ?: profilePath?.key ?: "PATH"
        val currentPath = profilePath?.value ?: inheritedPath?.value
        val helperPath = directory.toAbsolutePath().toString()
        val separator = if (windows) ';' else ':'
        val containsHelper = currentPath?.split(separator)?.any { it.equals(helperPath, ignoreCase = windows) } == true
        environment.keys.removeAll { it.equals("PATH", ignoreCase = windows) }
        environment[pathKey] =
            if (containsHelper) {
                requireNotNull(currentPath)
            } else if (currentPath == null) {
                helperPath
            } else {
                "$helperPath$separator$currentPath"
            }
        return profile.copy(environment = environment)
    }

    private fun install(
        name: String,
        executable: Boolean,
    ) {
        val content = checkNotNull(scripts[name])
        val target = directory.resolve(name)
        if (privateDirectory) target.toFile().deleteOnExit()
        if (Files.isRegularFile(target, NOFOLLOW_LINKS) && Files.readString(target) == content) {
            if (executable) makeExecutable(target)
            return
        }
        val temporary = Files.createTempFile(directory, ".$name-", ".tmp")
        try {
            Files.writeString(temporary, content)
            if (executable) makeExecutable(temporary)
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun makeExecutable(path: Path) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.setPermissions(
            PosixFilePermissions.fromString("rwxr-xr-x"),
        )
    }

    private companion object {
        private fun createPrivateDirectory(): Path {
            val fileSystem = FileSystems.getDefault()
            val permissions: FileAttribute<*> =
                when {
                    "posix" in fileSystem.supportedFileAttributeViews() ->
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
                    "acl" in fileSystem.supportedFileAttributeViews() -> {
                        val user = fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
                        val entry =
                            AclEntry
                                .newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(user)
                                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                                .build()
                        object : FileAttribute<List<AclEntry>> {
                            override fun name(): String = "acl:acl"

                            override fun value(): List<AclEntry> = listOf(entry)
                        }
                    }
                    else -> throw IOException("Filesystem cannot create private CLI helper directories")
                }
            return Files.createTempDirectory("ketraterm-cli-", permissions).also { it.toFile().deleteOnExit() }
        }

        val scripts =
            listOf("ketra", "ketra.bat").associateWith { name ->
                checkNotNull(KetraTermCli::class.java.getResourceAsStream("/io/github/ketraterm/app/cli/$name")) {
                    "Missing standalone CLI resource: $name"
                }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
    }
}
