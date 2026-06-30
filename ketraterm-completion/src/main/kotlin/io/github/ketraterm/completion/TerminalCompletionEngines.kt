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
package io.github.ketraterm.completion

/**
 * Factories for production completion engines.
 */
object TerminalCompletionEngines {
    /**
     * Creates a deterministic in-process engine backed by command specs.
     *
     * @param specs top-level command specs.
     * @return completion engine that evaluates [specs] without shell I/O.
     */
    @JvmStatic
    fun fromSpecs(specs: List<TerminalCommandSpec>): TerminalCompletionEngine =
        fromSources(
            listOf(
                TerminalCompletionSourceEntry(
                    source = TerminalCompletionSources.fromSpecs(specs),
                    priority = SPEC_SOURCE_PRIORITY,
                ),
            ),
        )

    /**
     * Creates a deterministic merged engine from prioritized completion sources.
     *
     * Candidates are deduplicated by replacement range and replacement text,
     * then ranked by source priority, candidate score, and stable text
     * tie-breakers before [TerminalCompletionRequest.maxCandidates] is applied.
     *
     * @param sources prioritized source registrations.
     * @return merged completion engine.
     */
    @JvmStatic
    fun fromSources(sources: List<TerminalCompletionSourceEntry>): TerminalCompletionEngine =
        if (sources.isEmpty()) {
            TerminalCompletionEngine.NONE
        } else {
            MergedCompletionEngine(sources)
        }

    /**
     * Creates a deterministic merged engine from equal-priority sources.
     *
     * @param sources completion sources queried in declaration order.
     * @return merged completion engine.
     */
    @JvmStatic
    fun fromSources(vararg sources: TerminalCompletionSource): TerminalCompletionEngine =
        fromSources(sources.map { TerminalCompletionSourceEntry(it) })

    private const val SPEC_SOURCE_PRIORITY = 0
}

/**
 * Factories for dependency-free completion sources.
 */
object TerminalCompletionSources {
    /**
     * Creates a deterministic source backed by static command specs.
     *
     * @param specs top-level command specs.
     * @return completion source that evaluates [specs] without shell I/O.
     */
    @JvmStatic
    fun fromSpecs(specs: List<TerminalCommandSpec>): TerminalCompletionSource = SpecCompletionSource(specs)
}

/**
 * Curated static command specs useful as a bootstrap source before richer
 * imported corpora and host context providers are available.
 */
object TerminalCommandSpecs {
    /**
     * Returns a small, deterministic default spec set for common developer
     * commands.
     *
     * @return built-in command specifications.
     */
    @JvmStatic
    fun defaults(): List<TerminalCommandSpec> =
        listOf(
            git(),
            gradle(),
            npm(),
            docker(),
        )

    /**
     * Returns a Git command spec focused on common porcelain workflows.
     *
     * @return Git command specification.
     */
    @JvmStatic
    fun git(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "git",
            description = "distributed version control",
            subcommands =
                listOf(
                    TerminalCommandSpec("status", "show working tree status"),
                    TerminalCommandSpec("add", "add file contents to the index"),
                    TerminalCommandSpec("commit", "record changes to the repository"),
                    TerminalCommandSpec("checkout", "switch branches or restore files", aliases = listOf("co")),
                    TerminalCommandSpec("switch", "switch branches"),
                    TerminalCommandSpec("branch", "list, create, or delete branches"),
                    TerminalCommandSpec("pull", "fetch from and integrate with another repository"),
                    TerminalCommandSpec("push", "update remote refs"),
                    TerminalCommandSpec("fetch", "download objects and refs"),
                    TerminalCommandSpec("merge", "join development histories"),
                    TerminalCommandSpec("rebase", "reapply commits on top of another base"),
                    TerminalCommandSpec("log", "show commit logs"),
                    TerminalCommandSpec("diff", "show changes between commits, trees, or files"),
                    TerminalCommandSpec("stash", "stash local modifications"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                    TerminalOptionSpec(listOf("-C"), "run as if git was started in path", requiresValue = true),
                ),
        )

    /**
     * Returns a Gradle command spec focused on common project tasks/options.
     *
     * @return Gradle command specification.
     */
    @JvmStatic
    fun gradle(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "gradle",
            description = "build automation",
            aliases = listOf("./gradlew", "gradlew"),
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "assemble and test the project"),
                    TerminalCommandSpec("test", "run tests"),
                    TerminalCommandSpec("check", "run verification tasks"),
                    TerminalCommandSpec("clean", "delete build outputs"),
                    TerminalCommandSpec("tasks", "list available tasks"),
                    TerminalCommandSpec("run", "run the application"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--info", "-i"), "set log level to info"),
                    TerminalOptionSpec(listOf("--debug", "-d"), "set log level to debug"),
                    TerminalOptionSpec(listOf("--scan"), "create a build scan"),
                    TerminalOptionSpec(listOf("--offline"), "build without network access"),
                ),
        )

    /**
     * Returns an npm command spec for common package workflows.
     *
     * @return npm command specification.
     */
    @JvmStatic
    fun npm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "npm",
            description = "JavaScript package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install package dependencies", aliases = listOf("i")),
                    TerminalCommandSpec("run", "run a package script"),
                    TerminalCommandSpec("test", "run the test script"),
                    TerminalCommandSpec("start", "run the start script"),
                    TerminalCommandSpec("update", "update packages"),
                    TerminalCommandSpec("publish", "publish a package"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--global", "-g"), "operate globally"),
                    TerminalOptionSpec(listOf("--save-dev", "-D"), "save to dev dependencies"),
                ),
        )

    /**
     * Returns a Docker command spec for common container workflows.
     *
     * @return Docker command specification.
     */
    @JvmStatic
    fun docker(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker",
            description = "container platform CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("ps", "list containers"),
                    TerminalCommandSpec("run", "run a container"),
                    TerminalCommandSpec("build", "build an image"),
                    TerminalCommandSpec("images", "list images"),
                    TerminalCommandSpec("pull", "pull an image"),
                    TerminalCommandSpec("push", "push an image"),
                    TerminalCommandSpec("compose", "manage Compose applications"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--context"), "select Docker context", requiresValue = true),
                ),
        )
}
