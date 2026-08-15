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

import io.github.ketraterm.completion.internal.GradleCompletionSyntax

/**
 * Curated static command specs useful as a bootstrap source before richer
 * imported corpora and host context providers are available.
 */
object TerminalCommandSpecs {
    /**
     * Returns a deterministic default spec set for common developer commands.
     *
     * @return built-in command specifications.
     */
    @JvmStatic
    fun defaults(): List<TerminalCommandSpec> =
        listOf(
            cd(),
            pushd(),
            ls(),
            cat(),
            mkdir(),
            rm(),
            cp(),
            mv(),
            code(),
            git(),
            gradle(),
            npm(),
            pnpm(),
            yarn(),
            bun(),
            docker(),
            dockerCompose(),
            cargo(),
            kubectl(),
            gh(),
            pip(),
            go(),
            aws(),
            ketra(),
        )

    private fun cd(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cd",
            description = "change directory",
            aliases = listOf("chdir", "sl", "set-location"),
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun pushd(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pushd",
            description = "change directory and save the current location",
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun ls(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ls",
            description = "list directory contents",
            aliases = listOf("dir"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-l"), "use a long listing format"),
                    TerminalOptionSpec(listOf("-a", "--all"), "do not ignore entries starting with ."),
                    TerminalOptionSpec(listOf("-h", "--human-readable"), "with -l, print sizes like 1K 234M 2G"),
                    TerminalOptionSpec(listOf("-t"), "sort by modification time"),
                    TerminalOptionSpec(listOf("-r", "--reverse"), "reverse order while sorting"),
                ),
        )

    private fun cat(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cat",
            description = "print file contents",
            aliases = listOf("type"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-n", "--number"), "number all output lines"),
                ),
        )

    private fun mkdir(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mkdir",
            description = "create directories",
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-p", "--parents"), "no error if existing, make parent directories as needed"),
                ),
        )

    private fun rm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "rm",
            description = "remove files or directories",
            aliases = listOf("del", "erase"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "-R", "--recursive"), "remove directories and their contents recursively"),
                    TerminalOptionSpec(listOf("-f", "--force"), "ignore nonexistent files and arguments, never prompt"),
                ),
        )

    private fun cp(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cp",
            description = "copy files or directories",
            aliases = listOf("copy"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "-R", "--recursive"), "copy directories recursively"),
                    TerminalOptionSpec(
                        listOf("-f", "--force"),
                        "if an existing destination file cannot be opened, remove it and try again",
                    ),
                ),
        )

    private fun mv(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mv",
            description = "move files or directories",
            aliases = listOf("move"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-f", "--force"), "do not prompt before overwriting"),
                ),
        )

    private fun code(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "code",
            description = "open files or directories in Visual Studio Code",
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "--reuse-window"), "force opening a file or folder in an already opened window"),
                    TerminalOptionSpec(listOf("-n", "--new-window"), "force opening a new window"),
                    TerminalOptionSpec(listOf("-g", "--goto"), "open a file at the path on the specified line and column"),
                    TerminalOptionSpec(listOf("-d", "--diff"), "compare two files with each other"),
                    TerminalOptionSpec(listOf("-w", "--wait"), "wait for the files to be closed before returning"),
                ),
        )

    /**
     * Returns a Git command spec focused on developer workflows.
     *
     * @return Git command specification.
     */
    private fun git(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "git",
            description = "distributed version control",
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "status",
                        description = "show working tree status",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--short", "-s"), "show status concisely"),
                                TerminalOptionSpec(listOf("--branch", "-b"), "show branch information"),
                                TerminalOptionSpec(listOf("--ignored"), "show ignored files as well"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "add",
                        description = "add file contents to the index",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-A", "--all"), "add changes from all tracked and untracked files"),
                                TerminalOptionSpec(
                                    listOf("-p", "--patch"),
                                    "interactively choose hunks of patch between the index and the work tree",
                                ),
                                TerminalOptionSpec(
                                    listOf("-u", "--update"),
                                    "update the index just where it already has an entry matching <pathspec>",
                                ),
                                TerminalOptionSpec(listOf("-f", "--force"), "allow adding otherwise ignored files"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "restore",
                        description = "restore working tree files",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--staged", "-S"), "restore the index"),
                                TerminalOptionSpec(listOf("--worktree", "-W"), "restore the working tree"),
                                TerminalOptionSpec(
                                    listOf("--source", "-s"),
                                    "which tree to restore from",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "rm",
                        description = "remove files from the working tree and index",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--cached"), "only remove from the index"),
                                TerminalOptionSpec(listOf("-r"), "allow recursive removal when a leading directory name is given"),
                                TerminalOptionSpec(listOf("-f", "--force"), "override the up-to-date check"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "commit",
                        description = "record changes to the repository",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-m", "--message"), "commit message", requiresValue = true),
                                TerminalOptionSpec(listOf("-a", "--all"), "stage all modified and deleted files"),
                                TerminalOptionSpec(listOf("--amend"), "amend previous commit"),
                                TerminalOptionSpec(listOf("--no-verify", "-n"), "bypass pre-commit and commit-msg hooks"),
                                TerminalOptionSpec(listOf("--allow-empty"), "allow recording a commit with no changes"),
                                TerminalOptionSpec(
                                    listOf("-s", "--signoff"),
                                    "add Signed-off-by line by the committer at the end of the commit log message",
                                ),
                                TerminalOptionSpec(
                                    listOf("--fixup"),
                                    "construct a fixup commit for use with rebase --autosquash",
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "checkout",
                        description = "switch branches or restore files",
                        aliases = listOf("co"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-b"), "create and check out a new branch", requiresValue = true),
                                TerminalOptionSpec(listOf("-B"), "create/reset and check out a branch", requiresValue = true),
                                TerminalOptionSpec(listOf("--detach"), "detach HEAD at named commit"),
                                TerminalOptionSpec(listOf("--theirs"), "check out their version for unmerged files"),
                                TerminalOptionSpec(listOf("--ours"), "check out our version for unmerged files"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "switch",
                        description = "switch branches",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-c", "--create"), "create and switch to a new branch", requiresValue = true),
                                TerminalOptionSpec(
                                    listOf("-C", "--force-create"),
                                    "create/reset and switch to a branch",
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(listOf("-d", "--detach"), "switch to a commit in detached HEAD state"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "branch",
                        description = "list, create, or delete branches",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-a", "--all"), "list both remote-tracking and local branches"),
                                TerminalOptionSpec(listOf("-r", "--remotes"), "list remote-tracking branches"),
                                TerminalOptionSpec(
                                    listOf("-d", "--delete"),
                                    "delete fully merged branch",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(
                                    listOf("-D"),
                                    "force delete branch",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(listOf("-m", "--move"), "move/rename a branch"),
                                TerminalOptionSpec(listOf("--merged"), "print only branches that are merged into HEAD"),
                                TerminalOptionSpec(listOf("--no-merged"), "print only branches that are not merged into HEAD"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "pull",
                        description = "fetch from and integrate with another repository",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-r", "--rebase"), "rebase current branch on top of upstream branch"),
                                TerminalOptionSpec(listOf("--autostash"), "automatically stash and unstash local changes"),
                                TerminalOptionSpec(
                                    listOf("--no-ff"),
                                    "create a merge commit even when the merge could be resolved as a fast-forward",
                                ),
                                TerminalOptionSpec(
                                    listOf("--ff-only"),
                                    "refuse to merge unless the current HEAD is already up to date or the merge can be resolved as a fast-forward",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "push",
                        description = "update remote refs",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-u", "--set-upstream"), "set upstream for git pull/status"),
                                TerminalOptionSpec(listOf("-f", "--force"), "force update"),
                                TerminalOptionSpec(
                                    listOf("--force-with-lease"),
                                    "force update only if remote branch matches expected state",
                                ),
                                TerminalOptionSpec(listOf("--all"), "push all branches"),
                                TerminalOptionSpec(listOf("--tags"), "push all tags"),
                                TerminalOptionSpec(listOf("-d", "--delete"), "delete all listed refs from the remote repository"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "fetch",
                        description = "download objects and refs",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--all"), "fetch all remotes"),
                                TerminalOptionSpec(
                                    listOf("-p", "--prune"),
                                    "remove local tracking branches that no longer exist on remote",
                                ),
                                TerminalOptionSpec(listOf("--tags"), "fetch all tags from the remote"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "merge",
                        description = "join development histories",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("--no-ff"),
                                    "create a merge commit even when the merge could be resolved as a fast-forward",
                                ),
                                TerminalOptionSpec(listOf("--ff-only"), "refuse to merge unless fast-forward"),
                                TerminalOptionSpec(listOf("--squash"), "squash commits into a single commit"),
                                TerminalOptionSpec(listOf("--abort"), "abort current conflict resolution"),
                                TerminalOptionSpec(listOf("--continue"), "continue current merge"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "rebase",
                        description = "reapply commits on top of another base",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-i", "--interactive"),
                                    "make a list of the commits which are about to be rebased",
                                ),
                                TerminalOptionSpec(
                                    listOf("--onto"),
                                    "starting point at which to create the new commits",
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(
                                    listOf("--continue"),
                                    "restart the rebasing process after editing a commit or resolving a merge conflict",
                                ),
                                TerminalOptionSpec(listOf("--abort"), "abort the rebase operation and reset HEAD to the original branch"),
                                TerminalOptionSpec(listOf("--skip"), "restart the rebasing process by skipping the current patch"),
                                TerminalOptionSpec(
                                    listOf("--autostash"),
                                    "automatically create a temporary stash entry before the operation begins",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "reset",
                        description = "reset current HEAD to the specified state",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--soft"), "do not touch the index file or the working tree at all"),
                                TerminalOptionSpec(listOf("--mixed"), "resets the index but not the working tree"),
                                TerminalOptionSpec(listOf("--hard"), "resets the index and working tree"),
                                TerminalOptionSpec(
                                    listOf("--merge"),
                                    "resets index and updates files in working tree that differ between commit and HEAD",
                                ),
                                TerminalOptionSpec(
                                    listOf("--keep"),
                                    "resets index, updates working tree if no uncommitted changes in tracked files",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "log",
                        description = "show commit logs",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--oneline"), "shorthand for --pretty=oneline --abbrev-commit"),
                                TerminalOptionSpec(listOf("--graph"), "draw a text-based graphical representation of the commit history"),
                                TerminalOptionSpec(listOf("--stat"), "generate a diffstat"),
                                TerminalOptionSpec(
                                    listOf("-n", "--max-count"),
                                    "limit the number of commits to output",
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "diff",
                        description = "show changes between commits, trees, or files",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--staged", "--cached"), "view changes staged for the next commit"),
                                TerminalOptionSpec(listOf("--name-only"), "show only names of changed files"),
                                TerminalOptionSpec(listOf("--name-status"), "show only names and status of changed files"),
                                TerminalOptionSpec(listOf("--stat"), "generate a diffstat"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "stash",
                        description = "stash local modifications",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("push", "save local modifications to a new stash entry"),
                                TerminalCommandSpec("pop", "remove a single stashed state from the stash list and apply it"),
                                TerminalCommandSpec("apply", "like pop, but do not remove the state from the stash list"),
                                TerminalCommandSpec("list", "list the stash entries that you currently have"),
                                TerminalCommandSpec("show", "show the changes recorded in the stash entry as a diff"),
                                TerminalCommandSpec("drop", "remove a single stash entry from the list of stash entries"),
                                TerminalCommandSpec("clear", "remove all the stash entries"),
                                TerminalCommandSpec(
                                    "branch",
                                    "create and check out a new branch starting from the commit at which the stash entry was originally created",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "remote",
                        description = "manage set of tracked repositories",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("add", "adds a remote named <name> for the repository at <url>"),
                                TerminalCommandSpec("rename", "renames the remote named <old> to <new>"),
                                TerminalCommandSpec("remove", "deletes the remote named <name>"),
                                TerminalCommandSpec("get-url", "retrieves the URLs for a remote"),
                                TerminalCommandSpec("set-url", "changes URLs for the remote"),
                                TerminalCommandSpec("show", "gives some information about the remote <name>"),
                                TerminalCommandSpec("prune", "deletes stale references associated with <name>"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "tag",
                        description = "create, list, delete or verify a tag object",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-a", "--annotate"), "make an unsigned, annotated tag object"),
                                TerminalOptionSpec(listOf("-d", "--delete"), "delete tags with given names"),
                                TerminalOptionSpec(listOf("-l", "--list"), "list tags"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "cherry-pick",
                        description = "apply the changes introduced by some existing commits",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--continue"), "continue operation in progress"),
                                TerminalOptionSpec(listOf("--abort"), "cancel operation and return to pre-sequence state"),
                                TerminalOptionSpec(listOf("--skip"), "skip current commit and continue with the rest of the sequence"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "revert",
                        description = "revert some existing commits",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--continue"), "continue operation in progress"),
                                TerminalOptionSpec(listOf("--abort"), "cancel operation and return to pre-sequence state"),
                                TerminalOptionSpec(listOf("--no-commit", "-n"), "do not automatically commit reverted changes"),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("-C"),
                        description = "run as if git was started in path",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )

    /**
     * Returns a Gradle command spec focused on common project tasks/options.
     *
     * @return Gradle command specification.
     */
    private fun gradle(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = GradleCompletionSyntax.COMMAND_NAME,
            description = "build automation",
            aliases = listOf("./gradlew", "gradlew"),
            repeatableSubcommands = true,
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "assemble and test the project"),
                    TerminalCommandSpec("test", "run tests"),
                    TerminalCommandSpec("check", "run verification tasks"),
                    TerminalCommandSpec("clean", "delete build outputs"),
                    TerminalCommandSpec("tasks", "list available tasks"),
                    TerminalCommandSpec("run", "run the application"),
                    TerminalCommandSpec("assemble", "assembles the outputs of this project"),
                    TerminalCommandSpec("bootRun", "runs this project as a Spring Boot application"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("--console"),
                        description = "console output style",
                        requiresValue = true,
                        valueCandidates = listOf("auto", "plain", "rich", "verbose"),
                    ),
                    TerminalOptionSpec(listOf("--info", "-i"), "set log level to info"),
                    TerminalOptionSpec(listOf("--debug", "-d"), "set log level to debug"),
                    TerminalOptionSpec(listOf("--scan"), "create a build scan"),
                    TerminalOptionSpec(listOf("--offline"), "build without network access"),
                    TerminalOptionSpec(listOf("--parallel"), "build projects in parallel"),
                    TerminalOptionSpec(listOf("--continuous", "-t"), "enables continuous build and execution"),
                    TerminalOptionSpec(
                        names = GradleCompletionSyntax.PROJECT_DIRECTORY_OPTION_NAMES,
                        description = "use the specified project directory",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )

    /**
     * Returns an npm command spec for common package workflows.
     *
     * @return npm command specification.
     */
    private fun npm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "npm",
            description = "JavaScript package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install package dependencies", aliases = listOf("i")),
                    TerminalCommandSpec(
                        name = "run",
                        description = "run a package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "run the test script", aliases = listOf("t")),
                    TerminalCommandSpec("start", "run the start script"),
                    TerminalCommandSpec("update", "update packages", aliases = listOf("up")),
                    TerminalCommandSpec("publish", "publish a package"),
                    TerminalCommandSpec("init", "create a package.json file"),
                    TerminalCommandSpec("outdated", "check for outdated packages"),
                    TerminalCommandSpec("audit", "run a security audit"),
                    TerminalCommandSpec("uninstall", "remove a package", aliases = listOf("un", "rm")),
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
     * Returns a pnpm command spec for fast disk space efficient package management.
     *
     * @return pnpm command specification.
     */
    private fun pnpm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pnpm",
            description = "Fast, disk space efficient package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install all dependencies for a project", aliases = listOf("i")),
                    TerminalCommandSpec("add", "install a package and any packages that it depends on"),
                    TerminalCommandSpec(
                        name = "run",
                        description = "runs a defined package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "runs an arbitrary command specified in package's test property", aliases = listOf("t")),
                    TerminalCommandSpec("build", "build package"),
                    TerminalCommandSpec("start", "runs an arbitrary command specified in package's start property"),
                    TerminalCommandSpec("remove", "removes packages from package.json", aliases = listOf("rm")),
                    TerminalCommandSpec(
                        "update",
                        "updates packages to their latest version based on the specified range",
                        aliases = listOf("up"),
                    ),
                    TerminalCommandSpec("exec", "executes a shell script in scope of the project"),
                    TerminalCommandSpec("dlx", "fetches a package from the registry and runs its default binary command"),
                    TerminalCommandSpec("publish", "publishes a package to the registry"),
                    TerminalCommandSpec("outdated", "check for outdated packages"),
                    TerminalCommandSpec("audit", "checks for known security issues with the installed packages"),
                    TerminalCommandSpec("why", "shows all packages that depend on the specified package"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        listOf("--recursive", "-r"),
                        "run the command on every package in subdirectories or in every workspace package",
                    ),
                    TerminalOptionSpec(listOf("--filter"), "filter packages by pattern", requiresValue = true),
                    TerminalOptionSpec(listOf("--save-dev", "-D"), "save to dev dependencies"),
                    TerminalOptionSpec(listOf("--save-peer"), "save to peer dependencies"),
                    TerminalOptionSpec(listOf("--global", "-g"), "install packages globally"),
                    TerminalOptionSpec(
                        listOf("--workspace-root", "-w"),
                        "run the command as if it was invoked in the root of the workspace",
                    ),
                    TerminalOptionSpec(listOf("--frozen-lockfile"), "do not generate a lockfile and fail if an update is needed"),
                ),
        )

    /**
     * Returns a Yarn command spec for package management.
     *
     * @return Yarn command specification.
     */
    private fun yarn(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "yarn",
            description = "JavaScript package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("add", "install a package and any packages that it depends on"),
                    TerminalCommandSpec("install", "install all the dependencies for a project", aliases = listOf("i")),
                    TerminalCommandSpec("remove", "remove a package"),
                    TerminalCommandSpec(
                        name = "run",
                        description = "run a defined package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "run the test script"),
                    TerminalCommandSpec("build", "build package"),
                    TerminalCommandSpec("start", "run the start script"),
                    TerminalCommandSpec("publish", "publish a package"),
                    TerminalCommandSpec("info", "show information about a package"),
                    TerminalCommandSpec("why", "show information about why a package is installed"),
                    TerminalCommandSpec("cache", "inspect the yarn cache directory"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--dev", "-D"), "save package to your devDependencies"),
                    TerminalOptionSpec(listOf("--peer", "-P"), "save package to your peerDependencies"),
                    TerminalOptionSpec(listOf("--exact", "-E"), "install package as exact version"),
                ),
        )

    /**
     * Returns a Bun command spec for all-in-one JavaScript runtime & toolkit.
     *
     * @return Bun command specification.
     */
    private fun bun(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "bun",
            description = "Fast all-in-one JavaScript runtime & toolkit",
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "run",
                        description = "run JavaScript and TypeScript files, executable packages, and package.json scripts",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
                    TerminalCommandSpec("test", "run unit tests with Bun's test runner"),
                    TerminalCommandSpec("install", "install dependencies for a project", aliases = listOf("i")),
                    TerminalCommandSpec("add", "add a dependency to package.json", aliases = listOf("a")),
                    TerminalCommandSpec("remove", "remove a dependency from package.json", aliases = listOf("rm")),
                    TerminalCommandSpec("update", "update dependencies to their latest version"),
                    TerminalCommandSpec("build", "bundle TypeScript and JavaScript into a single file"),
                    TerminalCommandSpec("dev", "start a development server with live reload"),
                    TerminalCommandSpec("create", "create a new project from a template"),
                    TerminalCommandSpec("pm", "manage packages"),
                    TerminalCommandSpec("upgrade", "upgrade Bun to the latest version"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--watch"), "automatically restart the process on file changes"),
                    TerminalOptionSpec(listOf("--hot"), "enable hot module reloading"),
                    TerminalOptionSpec(
                        listOf("--cwd"),
                        "set current working directory",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )

    /**
     * Returns a Docker command spec for common container workflows.
     *
     * @return Docker command specification.
     */
    private fun docker(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker",
            description = "container platform CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("ps", "list containers"),
                    TerminalCommandSpec("run", "run a command in a new container"),
                    TerminalCommandSpec("exec", "run a command in a running container"),
                    TerminalCommandSpec("build", "build an image from a Dockerfile"),
                    TerminalCommandSpec("images", "list images"),
                    TerminalCommandSpec("pull", "download an image from a registry"),
                    TerminalCommandSpec("push", "upload an image to a registry"),
                    TerminalCommandSpec("stop", "stop one or more running containers"),
                    TerminalCommandSpec("start", "start one or more stopped containers"),
                    TerminalCommandSpec("restart", "restart one or more containers"),
                    TerminalCommandSpec("rm", "remove one or more containers"),
                    TerminalCommandSpec("rmi", "remove one or more images"),
                    TerminalCommandSpec("logs", "fetch the logs of a container"),
                    TerminalCommandSpec("inspect", "return low-level information on Docker objects"),
                    TerminalCommandSpec("network", "manage networks"),
                    TerminalCommandSpec("volume", "manage volumes"),
                    TerminalCommandSpec("system", "manage Docker"),
                    TerminalCommandSpec(
                        name = "compose",
                        description = "manage Compose applications",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("up", "create and start containers"),
                                TerminalCommandSpec("down", "stop and remove containers"),
                                TerminalCommandSpec("ps", "list containers"),
                                TerminalCommandSpec("logs", "view output from containers"),
                                TerminalCommandSpec("build", "build or rebuild services"),
                                TerminalCommandSpec("exec", "execute a command in a running container"),
                                TerminalCommandSpec("run", "run a one-off command"),
                                TerminalCommandSpec("restart", "restart service containers"),
                                TerminalCommandSpec("stop", "stop services"),
                                TerminalCommandSpec("start", "start services"),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("--context"),
                        description = "select Docker context",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.DOCKER_CONTEXT,
                    ),
                ),
        )

    /**
     * Returns a docker-compose command spec for multi-container Docker applications.
     *
     * @return docker-compose command specification.
     */
    private fun dockerCompose(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker-compose",
            description = "define and run multi-container Docker applications",
            subcommands =
                listOf(
                    TerminalCommandSpec("up", "build, (re)create, start, and attach to containers for a service"),
                    TerminalCommandSpec("down", "stop and remove containers, networks, images, and volumes"),
                    TerminalCommandSpec("ps", "list containers"),
                    TerminalCommandSpec("logs", "view output from containers"),
                    TerminalCommandSpec("build", "build or rebuild services"),
                    TerminalCommandSpec("exec", "execute a command in a running container"),
                    TerminalCommandSpec("run", "run a one-off command on a service"),
                    TerminalCommandSpec("restart", "restart service containers"),
                    TerminalCommandSpec("stop", "stop running containers without removing them"),
                    TerminalCommandSpec("start", "start existing containers for a service"),
                    TerminalCommandSpec("config", "validate and view the Compose file"),
                    TerminalCommandSpec("pull", "pull service images"),
                    TerminalCommandSpec("push", "push service images"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        listOf("-f", "--file"),
                        "specify an alternate compose file",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(listOf("-p", "--project-name"), "specify an alternate project name", requiresValue = true),
                    TerminalOptionSpec(listOf("-d", "--detach"), "detached mode: Run containers in the background"),
                    TerminalOptionSpec(listOf("--build"), "build images before starting containers"),
                    TerminalOptionSpec(listOf("--remove-orphans"), "remove containers for services not defined in the Compose file"),
                ),
        )

    /**
     * Returns a Cargo command spec for Rust package management.
     *
     * @return Cargo command specification.
     */
    private fun cargo(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cargo",
            description = "Rust package manager",
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "compile the current package"),
                    TerminalCommandSpec("run", "run a binary or example of the local package"),
                    TerminalCommandSpec("test", "execute all unit and integration tests"),
                    TerminalCommandSpec("check", "analyze the current package and report errors"),
                    TerminalCommandSpec("clean", "remove artifacts that cargo has generated"),
                    TerminalCommandSpec("new", "create a new cargo package"),
                    TerminalCommandSpec("init", "create a new cargo package in an existing directory"),
                    TerminalCommandSpec("update", "update dependencies as recorded in the local lock file"),
                    TerminalCommandSpec("doc", "build this package's and its dependencies' documentation"),
                    TerminalCommandSpec("publish", "package and upload this package to the registry"),
                    TerminalCommandSpec("clippy", "checks a package to catch common mistakes and improve Rust code"),
                    TerminalCommandSpec("fmt", "formats all bin and lib files of the current crate"),
                    TerminalCommandSpec("add", "add dependencies to a manifest file"),
                    TerminalCommandSpec("remove", "remove dependencies from a manifest file", aliases = listOf("rm")),
                    TerminalCommandSpec("tree", "display a tree visualization of a dependency graph"),
                    TerminalCommandSpec("metadata", "output the resolved dependencies of a package"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-V"), "show version"),
                    TerminalOptionSpec(listOf("--verbose", "-v"), "use verbose output"),
                    TerminalOptionSpec(listOf("--quiet", "-q"), "do not print cargo log messages"),
                    TerminalOptionSpec(listOf("--release", "-r"), "build artifacts in release mode, with optimizations"),
                    TerminalOptionSpec(listOf("--all-targets"), "activate all available targets"),
                    TerminalOptionSpec(listOf("--workspace", "--all"), "process all packages in the workspace"),
                    TerminalOptionSpec(listOf("--features"), "space or comma separated list of features to activate", requiresValue = true),
                    TerminalOptionSpec(listOf("--all-features"), "activate all available features"),
                    TerminalOptionSpec(listOf("--no-default-features"), "do not activate the default feature"),
                    TerminalOptionSpec(
                        names = listOf("--manifest-path"),
                        description = "path to Cargo.toml",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(listOf("-p", "--package"), "package to operate on", requiresValue = true),
                ),
        )

    /**
     * Returns a kubectl command spec for Kubernetes cluster management.
     *
     * @return kubectl command specification.
     */
    private fun kubectl(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kubectl",
            description = "Kubernetes cluster CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "get",
                        description = "display one or many resources",
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = "resource",
                                    description = "resource type",
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("-o", "--output"),
                                    description = "output format",
                                    requiresValue = true,
                                    valueCandidates = listOf("yaml", "json", "wide", "name"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-A", "--all-namespaces"),
                                    "if present, list the requested object(s) across all namespaces",
                                ),
                                TerminalOptionSpec(listOf("-l", "--selector"), "selector (label query) to filter on", requiresValue = true),
                                TerminalOptionSpec(
                                    listOf("-w", "--watch"),
                                    "after listing/getting the requested object, watch for changes",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "describe",
                        description = "show details of a specific resource or group of resources",
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = "resource",
                                    description = "resource type",
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-A", "--all-namespaces"),
                                    "if present, describe the requested object(s) across all namespaces",
                                ),
                                TerminalOptionSpec(listOf("-l", "--selector"), "selector (label query) to filter on", requiresValue = true),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "logs",
                        description = "print the logs for a container in a pod",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-f", "--follow"), "specify if the logs should be streamed"),
                                TerminalOptionSpec(
                                    listOf("-p", "--previous"),
                                    "if true, print the logs for the previous instance of the container in a pod",
                                ),
                                TerminalOptionSpec(listOf("-c", "--container"), "print the logs of this container", requiresValue = true),
                                TerminalOptionSpec(listOf("--tail"), "lines of recent log file to display", requiresValue = true),
                                TerminalOptionSpec(listOf("--timestamps"), "include timestamps on each line in the log output"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "exec",
                        description = "execute a command in a container",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-i", "--stdin"), "pass stdin to the container"),
                                TerminalOptionSpec(listOf("-t", "--tty"), "stdin is a TTY"),
                                TerminalOptionSpec(listOf("-c", "--container"), "container name", requiresValue = true),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "apply",
                        description = "apply a configuration to a resource by file name or stdin",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-f", "--filename"),
                                    "the files that contain the configurations to apply",
                                    requiresValue = true,
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(listOf("-R", "--recursive"), "process the directory used in -f, --filename recursively"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "delete",
                        description = "delete resources by file names, stdin, resources and names",
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = "resource",
                                    description = "resource type",
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-f", "--filename"),
                                    "containing the resource to delete",
                                    requiresValue = true,
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(listOf("-l", "--selector"), "selector (label query) to filter on", requiresValue = true),
                                TerminalOptionSpec(
                                    listOf("--force"),
                                    "if true, immediately remove resources from API and bypass graceful deletion",
                                ),
                            ),
                    ),
                    TerminalCommandSpec("port-forward", "forward one or more local ports to a pod"),
                    TerminalCommandSpec("config", "modify kubeconfig files"),
                    TerminalCommandSpec("create", "create a resource from a file or from stdin"),
                    TerminalCommandSpec("edit", "edit a resource on the server"),
                    TerminalCommandSpec("top", "display resource (CPU/memory) usage"),
                    TerminalCommandSpec("rollout", "manage the rollout of a resource"),
                    TerminalCommandSpec("scale", "set a new size for a deployment, replicaSet, or replicationController"),
                    TerminalCommandSpec("drain", "drain node in preparation for maintenance"),
                    TerminalCommandSpec("cordon", "mark node as unschedulable"),
                    TerminalCommandSpec("uncordon", "mark node as schedulable"),
                    TerminalCommandSpec("run", "run a particular image on the cluster"),
                    TerminalCommandSpec("explain", "get documentation for a resource"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(
                        names = listOf("--kubeconfig"),
                        description = "path to the kubeconfig file",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--namespace", "-n"),
                        description = "kubernetes namespace to use",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--context"),
                        description = "name of the kubeconfig context to use",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.KUBERNETES_CONTEXT,
                    ),
                ),
        )

    /**
     * Returns a GitHub CLI spec for common workflows.
     *
     * @return GitHub CLI command specification.
     */
    private fun gh(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "gh",
            description = "GitHub CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("pr", "manage pull requests"),
                    TerminalCommandSpec("issue", "manage issues"),
                    TerminalCommandSpec("repo", "manage repositories"),
                    TerminalCommandSpec("auth", "login, logout, and select active accounts"),
                    TerminalCommandSpec("run", "view details of workflow runs"),
                    TerminalCommandSpec("workflow", "view and run GitHub Actions workflows"),
                    TerminalCommandSpec("gist", "manage gists"),
                    TerminalCommandSpec("secret", "manage GitHub secrets"),
                    TerminalCommandSpec("api", "make an authenticated GitHub API request"),
                    TerminalCommandSpec("completion", "generate shell completion scripts"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                ),
        )

    /**
     * Returns a pip command spec for Python package management.
     *
     * @return pip command specification.
     */
    private fun pip(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pip",
            description = "Python package installer",
            subcommands =
                listOf(
                    TerminalCommandSpec("install", "install packages"),
                    TerminalCommandSpec("uninstall", "uninstall packages"),
                    TerminalCommandSpec("list", "list installed packages"),
                    TerminalCommandSpec("show", "show information about installed packages"),
                    TerminalCommandSpec("search", "search PyPI for packages"),
                    TerminalCommandSpec("freeze", "output installed packages in requirements format"),
                    TerminalCommandSpec("wheel", "build wheels from your requirements"),
                    TerminalCommandSpec("cache", "inspect and manage pip's wheel cache"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-V"), "show version"),
                    TerminalOptionSpec(listOf("--verbose", "-v"), "give more output"),
                    TerminalOptionSpec(listOf("--quiet", "-q"), "give less output"),
                ),
        )

    /**
     * Returns a Go command spec for Go toolchain development.
     *
     * @return Go command specification.
     */
    private fun go(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "go",
            description = "Go toolchain CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("build", "compile packages and dependencies"),
                    TerminalCommandSpec("run", "compile and run Go program"),
                    TerminalCommandSpec("test", "test packages"),
                    TerminalCommandSpec("fmt", "gofmt (reformat) package sources"),
                    TerminalCommandSpec("get", "add dependencies to current module and install them"),
                    TerminalCommandSpec("install", "compile and install packages and dependencies"),
                    TerminalCommandSpec("mod", "module maintenance"),
                    TerminalCommandSpec("clean", "remove object files and cached files"),
                    TerminalCommandSpec("doc", "show documentation for package or symbol"),
                    TerminalCommandSpec("vet", "report likely mistakes in packages"),
                    TerminalCommandSpec("version", "print Go version"),
                    TerminalCommandSpec("env", "print Go environment information"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("-h"), "show help"),
                ),
        )

    /**
     * Returns an AWS CLI command spec for cloud resource operations.
     *
     * @return AWS CLI command specification.
     */
    private fun aws(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "aws",
            description = "AWS Unified Command Line Interface",
            subcommands =
                listOf(
                    TerminalCommandSpec("s3", "manage S3 storage resources"),
                    TerminalCommandSpec("ec2", "manage elastic compute cloud resources"),
                    TerminalCommandSpec("rds", "manage relational database service instances"),
                    TerminalCommandSpec("dynamodb", "manage DynamoDB tables and items"),
                    TerminalCommandSpec("lambda", "manage AWS Lambda functions"),
                    TerminalCommandSpec("iam", "manage Identity and Access Management"),
                    TerminalCommandSpec("sts", "manage Security Token Service credentials"),
                    TerminalCommandSpec("configure", "configure AWS CLI settings"),
                    TerminalCommandSpec("cloudformation", "manage CloudFormation stacks"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("--profile"),
                        description = "select AWS CLI profile to use",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.AWS_PROFILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--region"),
                        description = "AWS region to target",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.AWS_REGION,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--output"),
                        description = "output format json, text, table",
                        requiresValue = true,
                        valueCandidates = listOf("json", "text", "table", "yaml", "yaml-stream"),
                    ),
                ),
        )

    /**
     * Returns a KetraTerm launcher command spec.
     *
     * @return KetraTerm launcher command specification.
     */
    private fun ketra(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ketra",
            description = "KetraTerm launcher CLI",
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--profile", "-p"), "launch with specific shell profile", requiresValue = true),
                    TerminalOptionSpec(
                        names = listOf("--directory", "-d"),
                        description = "start in specific directory",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )

    private val KUBECTL_RESOURCES =
        listOf(
            "pods",
            "services",
            "deployments",
            "configmaps",
            "secrets",
            "namespaces",
            "nodes",
            "ingress",
            "statefulsets",
            "persistentvolumeclaims",
            "events",
            "cronjobs",
        )
}
