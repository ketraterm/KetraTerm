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
package io.github.ketraterm.app.deployment

import java.io.File

object VersionNormalization {
    /**
     * Normalizes a SemVer project version (e.g. 0.1.0-alpha02) to a purely numeric,
     * monotonically increasing version (e.g. 1.1.2) suitable for native installers.
     *
     * Algorithm:
     *   nativeMajor = projectMajor + 1
     *   nativeMinor = projectMinor
     *   nativeBuild = projectPatch * 100 + stageCode
     *
     * Stage codes:
     *   alpha 1-19 -> 1-19
     *   beta 1-19  -> 21-39
     *   rc 1-19    -> 41-59
     *   stable     -> 99
     */
    fun normalize(projectVersion: String): String {
        // Strip SNAPSHOT suffix if present
        val cleanVersion = projectVersion.removeSuffix("-SNAPSHOT").trim()

        val parts = cleanVersion.split('.')
        if (parts.size != 3) {
            throw IllegalArgumentException("Version must be in MAJOR.MINOR.PATCH[-SUFFIX] format. Got: $projectVersion")
        }

        val major = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Major version must be numeric. Got: ${parts[0]}")
        val minor = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Minor version must be numeric. Got: ${parts[1]}")

        val patchFull = parts[2]
        val patchStr: String
        val suffix: String

        if ('-' in patchFull) {
            val hyphenIdx = patchFull.indexOf('-')
            patchStr = patchFull.substring(0, hyphenIdx)
            suffix = patchFull.substring(hyphenIdx + 1)
        } else {
            patchStr = patchFull
            suffix = ""
        }

        val patch = patchStr.toIntOrNull() ?: throw IllegalArgumentException("Patch version must be numeric. Got: $patchStr")

        val nativeMajor = major + 1
        val nativeMinor = minor
        val stageCode =
            when {
                suffix.isEmpty() -> 99
                suffix.startsWith("alpha") -> {
                    val num =
                        suffix.removePrefix("alpha").toIntOrNull()
                            ?: throw IllegalArgumentException("Alpha release version must be numeric. Got suffix: $suffix")
                    if (num !in 1..19) {
                        throw IllegalArgumentException("Alpha version must be between 1 and 19. Got: $num")
                    }
                    num
                }
                suffix.startsWith("beta") -> {
                    val num =
                        suffix.removePrefix("beta").toIntOrNull()
                            ?: throw IllegalArgumentException("Beta release version must be numeric. Got suffix: $suffix")
                    if (num !in 1..19) {
                        throw IllegalArgumentException("Beta version must be between 1 and 19. Got: $num")
                    }
                    20 + num
                }
                suffix.startsWith("rc") -> {
                    val num =
                        suffix.removePrefix("rc").toIntOrNull()
                            ?: throw IllegalArgumentException("RC release version must be numeric. Got suffix: $suffix")
                    if (num !in 1..19) {
                        throw IllegalArgumentException("RC version must be between 1 and 19. Got: $num")
                    }
                    40 + num
                }
                else -> throw IllegalArgumentException("Unsupported prerelease suffix: $suffix (must be alphaX, betaX, or rcX)")
            }

        val nativeBuild = patch * 100 + stageCode
        return "$nativeMajor.$nativeMinor.$nativeBuild"
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: VersionNormalizationKt <project-version> [output-file-path]")
        System.exit(1)
    }

    try {
        val normalized = VersionNormalization.normalize(args[0])
        if (args.size > 1) {
            val outputFile = File(args[1])
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(normalized)
            println("Normalized version $normalized written to ${outputFile.absolutePath}")
        } else {
            println(normalized)
        }
    } catch (e: Exception) {
        System.err.println("Error normalizing version: ${e.message}")
        System.exit(1)
    }
}
