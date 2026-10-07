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
package io.github.ketraterm.completion.model

/**
 * Curated package and project management specifications (NPM, PNPM, Yarn, Bun, Cargo, Pip, Go, GitHub CLI).
 */
internal object PackageManagerCommandSpecs {
    fun npm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "npm",
            description = commandSpecText("spec.npm.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("install", commandSpecText("spec.npm.install.description"), aliases = listOf("i")),
                    TerminalCommandSpec(
                        name = "run",
                        description = commandSpecText("spec.npm.run.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", commandSpecText("spec.npm.test.description"), aliases = listOf("t")),
                    TerminalCommandSpec("start", commandSpecText("spec.npm.start.description")),
                    TerminalCommandSpec("update", commandSpecText("spec.npm.update.description"), aliases = listOf("up")),
                    TerminalCommandSpec("publish", commandSpecText("spec.npm.publish.description")),
                    TerminalCommandSpec("init", commandSpecText("spec.npm.init.description")),
                    TerminalCommandSpec("outdated", commandSpecText("spec.npm.outdated.description")),
                    TerminalCommandSpec("audit", commandSpecText("spec.npm.audit.description")),
                    TerminalCommandSpec("uninstall", commandSpecText("spec.npm.uninstall.description"), aliases = listOf("un", "rm")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.npm.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.npm.option.--version.description")),
                    TerminalOptionSpec(listOf("--global", "-g"), commandSpecText("spec.npm.option.--global.description")),
                    TerminalOptionSpec(listOf("--save-dev", "-D"), commandSpecText("spec.npm.option.--save-dev.description")),
                ),
        )

    fun pnpm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pnpm",
            description = commandSpecText("spec.pnpm.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("install", commandSpecText("spec.pnpm.install.description"), aliases = listOf("i")),
                    TerminalCommandSpec("add", commandSpecText("spec.pnpm.add.description")),
                    TerminalCommandSpec(
                        name = "run",
                        description = commandSpecText("spec.pnpm.run.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", commandSpecText("spec.pnpm.test.description"), aliases = listOf("t")),
                    TerminalCommandSpec("build", commandSpecText("spec.pnpm.build.description")),
                    TerminalCommandSpec("start", commandSpecText("spec.pnpm.start.description")),
                    TerminalCommandSpec("remove", commandSpecText("spec.pnpm.remove.description"), aliases = listOf("rm")),
                    TerminalCommandSpec(
                        "update",
                        commandSpecText("spec.pnpm.update.description"),
                        aliases = listOf("up"),
                    ),
                    TerminalCommandSpec("exec", commandSpecText("spec.pnpm.exec.description")),
                    TerminalCommandSpec("dlx", commandSpecText("spec.pnpm.dlx.description")),
                    TerminalCommandSpec("publish", commandSpecText("spec.pnpm.publish.description")),
                    TerminalCommandSpec("outdated", commandSpecText("spec.pnpm.outdated.description")),
                    TerminalCommandSpec("audit", commandSpecText("spec.pnpm.audit.description")),
                    TerminalCommandSpec("why", commandSpecText("spec.pnpm.why.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.pnpm.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.pnpm.option.--version.description")),
                    TerminalOptionSpec(
                        listOf("--recursive", "-r"),
                        commandSpecText("spec.pnpm.option.--recursive.description"),
                    ),
                    TerminalOptionSpec(listOf("--filter"), commandSpecText("spec.pnpm.option.--filter.description"), requiresValue = true),
                    TerminalOptionSpec(listOf("--save-dev", "-D"), commandSpecText("spec.pnpm.option.--save-dev.description")),
                    TerminalOptionSpec(listOf("--save-peer"), commandSpecText("spec.pnpm.option.--save-peer.description")),
                    TerminalOptionSpec(listOf("--global", "-g"), commandSpecText("spec.pnpm.option.--global.description")),
                    TerminalOptionSpec(
                        listOf("--workspace-root", "-w"),
                        commandSpecText("spec.pnpm.option.--workspace-root.description"),
                    ),
                    TerminalOptionSpec(listOf("--frozen-lockfile"), commandSpecText("spec.pnpm.option.--frozen-lockfile.description")),
                ),
        )

    fun yarn(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "yarn",
            description = commandSpecText("spec.yarn.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("add", commandSpecText("spec.yarn.add.description")),
                    TerminalCommandSpec("install", commandSpecText("spec.yarn.install.description"), aliases = listOf("i")),
                    TerminalCommandSpec("remove", commandSpecText("spec.yarn.remove.description")),
                    TerminalCommandSpec(
                        name = "run",
                        description = commandSpecText("spec.yarn.run.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", commandSpecText("spec.yarn.test.description")),
                    TerminalCommandSpec("build", commandSpecText("spec.yarn.build.description")),
                    TerminalCommandSpec("start", commandSpecText("spec.yarn.start.description")),
                    TerminalCommandSpec("publish", commandSpecText("spec.yarn.publish.description")),
                    TerminalCommandSpec("info", commandSpecText("spec.yarn.info.description")),
                    TerminalCommandSpec("why", commandSpecText("spec.yarn.why.description")),
                    TerminalCommandSpec("cache", commandSpecText("spec.yarn.cache.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.yarn.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.yarn.option.--version.description")),
                    TerminalOptionSpec(listOf("--dev", "-D"), commandSpecText("spec.yarn.option.--dev.description")),
                    TerminalOptionSpec(listOf("--peer", "-P"), commandSpecText("spec.yarn.option.--peer.description")),
                    TerminalOptionSpec(listOf("--exact", "-E"), commandSpecText("spec.yarn.option.--exact.description")),
                ),
        )

    fun bun(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "bun",
            description = commandSpecText("spec.bun.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "run",
                        description = commandSpecText("spec.bun.run.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", commandSpecText("spec.bun.test.description")),
                    TerminalCommandSpec("install", commandSpecText("spec.bun.install.description"), aliases = listOf("i")),
                    TerminalCommandSpec("add", commandSpecText("spec.bun.add.description"), aliases = listOf("a")),
                    TerminalCommandSpec("remove", commandSpecText("spec.bun.remove.description"), aliases = listOf("rm")),
                    TerminalCommandSpec("update", commandSpecText("spec.bun.update.description")),
                    TerminalCommandSpec("build", commandSpecText("spec.bun.build.description")),
                    TerminalCommandSpec("dev", commandSpecText("spec.bun.dev.description")),
                    TerminalCommandSpec("create", commandSpecText("spec.bun.create.description")),
                    TerminalCommandSpec("pm", commandSpecText("spec.bun.pm.description")),
                    TerminalCommandSpec("upgrade", commandSpecText("spec.bun.upgrade.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.bun.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.bun.option.--version.description")),
                    TerminalOptionSpec(listOf("--watch"), commandSpecText("spec.bun.option.--watch.description")),
                    TerminalOptionSpec(listOf("--hot"), commandSpecText("spec.bun.option.--hot.description")),
                    TerminalOptionSpec(
                        listOf("--cwd"),
                        commandSpecText("spec.bun.option.--cwd.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )

    fun cargo(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cargo",
            description = commandSpecText("spec.cargo.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("build", commandSpecText("spec.cargo.build.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.cargo.run.description")),
                    TerminalCommandSpec("test", commandSpecText("spec.cargo.test.description")),
                    TerminalCommandSpec("check", commandSpecText("spec.cargo.check.description")),
                    TerminalCommandSpec("clean", commandSpecText("spec.cargo.clean.description")),
                    TerminalCommandSpec("new", commandSpecText("spec.cargo.new.description")),
                    TerminalCommandSpec("init", commandSpecText("spec.cargo.init.description")),
                    TerminalCommandSpec("update", commandSpecText("spec.cargo.update.description")),
                    TerminalCommandSpec("doc", commandSpecText("spec.cargo.doc.description")),
                    TerminalCommandSpec("publish", commandSpecText("spec.cargo.publish.description")),
                    TerminalCommandSpec("clippy", commandSpecText("spec.cargo.clippy.description")),
                    TerminalCommandSpec("fmt", commandSpecText("spec.cargo.fmt.description")),
                    TerminalCommandSpec("add", commandSpecText("spec.cargo.add.description")),
                    TerminalCommandSpec("remove", commandSpecText("spec.cargo.remove.description"), aliases = listOf("rm")),
                    TerminalCommandSpec("tree", commandSpecText("spec.cargo.tree.description")),
                    TerminalCommandSpec("metadata", commandSpecText("spec.cargo.metadata.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.cargo.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-V"), commandSpecText("spec.cargo.option.--version.description")),
                    TerminalOptionSpec(listOf("--verbose", "-v"), commandSpecText("spec.cargo.option.--verbose.description")),
                    TerminalOptionSpec(listOf("--quiet", "-q"), commandSpecText("spec.cargo.option.--quiet.description")),
                    TerminalOptionSpec(listOf("--release", "-r"), commandSpecText("spec.cargo.option.--release.description")),
                    TerminalOptionSpec(listOf("--all-targets"), commandSpecText("spec.cargo.option.--all-targets.description")),
                    TerminalOptionSpec(listOf("--workspace", "--all"), commandSpecText("spec.cargo.option.--workspace.description")),
                    TerminalOptionSpec(
                        listOf("--features"),
                        commandSpecText("spec.cargo.option.--features.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("--all-features"), commandSpecText("spec.cargo.option.--all-features.description")),
                    TerminalOptionSpec(
                        listOf("--no-default-features"),
                        commandSpecText("spec.cargo.option.--no-default-features.description"),
                    ),
                    TerminalOptionSpec(
                        names = listOf("--manifest-path"),
                        description = commandSpecText("spec.cargo.option.--manifest-path.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        listOf("-p", "--package"),
                        commandSpecText("spec.cargo.option.-p.description"),
                        requiresValue = true,
                    ),
                ),
        )

    fun pip(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pip",
            description = commandSpecText("spec.pip.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("install", commandSpecText("spec.pip.install.description")),
                    TerminalCommandSpec("uninstall", commandSpecText("spec.pip.uninstall.description")),
                    TerminalCommandSpec("list", commandSpecText("spec.pip.list.description")),
                    TerminalCommandSpec("show", commandSpecText("spec.pip.show.description")),
                    TerminalCommandSpec("search", commandSpecText("spec.pip.search.description")),
                    TerminalCommandSpec("freeze", commandSpecText("spec.pip.freeze.description")),
                    TerminalCommandSpec("wheel", commandSpecText("spec.pip.wheel.description")),
                    TerminalCommandSpec("cache", commandSpecText("spec.pip.cache.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.pip.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-V"), commandSpecText("spec.pip.option.--version.description")),
                    TerminalOptionSpec(listOf("--verbose", "-v"), commandSpecText("spec.pip.option.--verbose.description")),
                    TerminalOptionSpec(listOf("--quiet", "-q"), commandSpecText("spec.pip.option.--quiet.description")),
                ),
        )

    fun go(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "go",
            description = commandSpecText("spec.go.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("build", commandSpecText("spec.go.build.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.go.run.description")),
                    TerminalCommandSpec("test", commandSpecText("spec.go.test.description")),
                    TerminalCommandSpec("fmt", commandSpecText("spec.go.fmt.description")),
                    TerminalCommandSpec("get", commandSpecText("spec.go.get.description")),
                    TerminalCommandSpec("install", commandSpecText("spec.go.install.description")),
                    TerminalCommandSpec("mod", commandSpecText("spec.go.mod.description")),
                    TerminalCommandSpec("clean", commandSpecText("spec.go.clean.description")),
                    TerminalCommandSpec("doc", commandSpecText("spec.go.doc.description")),
                    TerminalCommandSpec("vet", commandSpecText("spec.go.vet.description")),
                    TerminalCommandSpec("version", commandSpecText("spec.go.version.description")),
                    TerminalCommandSpec("env", commandSpecText("spec.go.env.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-h"), commandSpecText("spec.go.option.-h.description")),
                ),
        )

    fun gh(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "gh",
            description = commandSpecText("spec.gh.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("pr", commandSpecText("spec.gh.pr.description")),
                    TerminalCommandSpec("issue", commandSpecText("spec.gh.issue.description")),
                    TerminalCommandSpec("repo", commandSpecText("spec.gh.repo.description")),
                    TerminalCommandSpec("auth", commandSpecText("spec.gh.auth.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.gh.run.description")),
                    TerminalCommandSpec("workflow", commandSpecText("spec.gh.workflow.description")),
                    TerminalCommandSpec("gist", commandSpecText("spec.gh.gist.description")),
                    TerminalCommandSpec("secret", commandSpecText("spec.gh.secret.description")),
                    TerminalCommandSpec("api", commandSpecText("spec.gh.api.description")),
                    TerminalCommandSpec("completion", commandSpecText("spec.gh.completion.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), commandSpecText("spec.gh.option.--help.description")),
                    TerminalOptionSpec(listOf("--version"), commandSpecText("spec.gh.option.--version.description")),
                ),
        )
}
