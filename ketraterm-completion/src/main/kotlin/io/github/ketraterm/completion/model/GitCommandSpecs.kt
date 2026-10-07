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
 * Curated Git CLI specifications for developer workflows.
 */
internal object GitCommandSpecs {
    fun git(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "git",
            description = commandSpecText("spec.git.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "status",
                        description = commandSpecText("spec.git.status.description"),
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--short", "-s"), commandSpecText("spec.git.status.option.--short.description")),
                                TerminalOptionSpec(
                                    listOf("--branch", "-b"),
                                    commandSpecText("spec.git.status.option.--branch.description"),
                                ),
                                TerminalOptionSpec(listOf("--ignored"), commandSpecText("spec.git.status.option.--ignored.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "add",
                        description = commandSpecText("spec.git.add.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-A", "--all"), commandSpecText("spec.git.add.option.-A.description")),
                                TerminalOptionSpec(
                                    listOf("-p", "--patch"),
                                    commandSpecText("spec.git.add.option.-p.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-u", "--update"),
                                    commandSpecText("spec.git.add.option.-u.description"),
                                ),
                                TerminalOptionSpec(listOf("-f", "--force"), commandSpecText("spec.git.add.option.-f.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "restore",
                        description = commandSpecText("spec.git.restore.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("--staged", "-S"),
                                    commandSpecText("spec.git.restore.option.--staged.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--worktree", "-W"),
                                    commandSpecText("spec.git.restore.option.--worktree.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--source", "-s"),
                                    commandSpecText("spec.git.restore.option.--source.description"),
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "rm",
                        description = commandSpecText("spec.git.rm.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--cached"), commandSpecText("spec.git.rm.option.--cached.description")),
                                TerminalOptionSpec(listOf("-r"), commandSpecText("spec.git.rm.option.-r.description")),
                                TerminalOptionSpec(listOf("-f", "--force"), commandSpecText("spec.git.rm.option.-f.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "commit",
                        description = commandSpecText("spec.git.commit.description"),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-m", "--message"),
                                    commandSpecText("spec.git.commit.option.-m.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(listOf("-a", "--all"), commandSpecText("spec.git.commit.option.-a.description")),
                                TerminalOptionSpec(listOf("--amend"), commandSpecText("spec.git.commit.option.--amend.description")),
                                TerminalOptionSpec(
                                    listOf("--no-verify", "-n"),
                                    commandSpecText("spec.git.commit.option.--no-verify.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--allow-empty"),
                                    commandSpecText("spec.git.commit.option.--allow-empty.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-s", "--signoff"),
                                    commandSpecText("spec.git.commit.option.-s.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--fixup"),
                                    commandSpecText("spec.git.commit.option.--fixup.description"),
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "checkout",
                        description = commandSpecText("spec.git.checkout.description"),
                        aliases = listOf("co"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-b"),
                                    commandSpecText("spec.git.checkout.option.-b.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(
                                    listOf("-B"),
                                    commandSpecText("spec.git.checkout.option.-B.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(listOf("--detach"), commandSpecText("spec.git.checkout.option.--detach.description")),
                                TerminalOptionSpec(listOf("--theirs"), commandSpecText("spec.git.checkout.option.--theirs.description")),
                                TerminalOptionSpec(listOf("--ours"), commandSpecText("spec.git.checkout.option.--ours.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "switch",
                        description = commandSpecText("spec.git.switch.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-c", "--create"),
                                    commandSpecText("spec.git.switch.option.-c.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(
                                    listOf("-C", "--force-create"),
                                    commandSpecText("spec.git.switch.option.-C.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(listOf("-d", "--detach"), commandSpecText("spec.git.switch.option.-d.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "branch",
                        description = commandSpecText("spec.git.branch.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-a", "--all"), commandSpecText("spec.git.branch.option.-a.description")),
                                TerminalOptionSpec(listOf("-r", "--remotes"), commandSpecText("spec.git.branch.option.-r.description")),
                                TerminalOptionSpec(
                                    listOf("-d", "--delete"),
                                    commandSpecText("spec.git.branch.option.-d.description"),
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(
                                    listOf("-D"),
                                    commandSpecText("spec.git.branch.option.-D.description"),
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(listOf("-m", "--move"), commandSpecText("spec.git.branch.option.-m.description")),
                                TerminalOptionSpec(listOf("--merged"), commandSpecText("spec.git.branch.option.--merged.description")),
                                TerminalOptionSpec(
                                    listOf("--no-merged"),
                                    commandSpecText("spec.git.branch.option.--no-merged.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "pull",
                        description = commandSpecText("spec.git.pull.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-r", "--rebase"), commandSpecText("spec.git.pull.option.-r.description")),
                                TerminalOptionSpec(listOf("--autostash"), commandSpecText("spec.git.pull.option.--autostash.description")),
                                TerminalOptionSpec(
                                    listOf("--no-ff"),
                                    commandSpecText("spec.git.pull.option.--no-ff.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--ff-only"),
                                    commandSpecText("spec.git.pull.option.--ff-only.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "push",
                        description = commandSpecText("spec.git.push.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-u", "--set-upstream"), commandSpecText("spec.git.push.option.-u.description")),
                                TerminalOptionSpec(listOf("-f", "--force"), commandSpecText("spec.git.push.option.-f.description")),
                                TerminalOptionSpec(
                                    listOf("--force-with-lease"),
                                    commandSpecText("spec.git.push.option.--force-with-lease.description"),
                                ),
                                TerminalOptionSpec(listOf("--all"), commandSpecText("spec.git.push.option.--all.description")),
                                TerminalOptionSpec(listOf("--tags"), commandSpecText("spec.git.push.option.--tags.description")),
                                TerminalOptionSpec(listOf("-d", "--delete"), commandSpecText("spec.git.push.option.-d.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "fetch",
                        description = commandSpecText("spec.git.fetch.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--all"), commandSpecText("spec.git.fetch.option.--all.description")),
                                TerminalOptionSpec(
                                    listOf("-p", "--prune"),
                                    commandSpecText("spec.git.fetch.option.-p.description"),
                                ),
                                TerminalOptionSpec(listOf("--tags"), commandSpecText("spec.git.fetch.option.--tags.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "merge",
                        description = commandSpecText("spec.git.merge.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("--no-ff"),
                                    commandSpecText("spec.git.merge.option.--no-ff.description"),
                                ),
                                TerminalOptionSpec(listOf("--ff-only"), commandSpecText("spec.git.merge.option.--ff-only.description")),
                                TerminalOptionSpec(listOf("--squash"), commandSpecText("spec.git.merge.option.--squash.description")),
                                TerminalOptionSpec(listOf("--abort"), commandSpecText("spec.git.merge.option.--abort.description")),
                                TerminalOptionSpec(listOf("--continue"), commandSpecText("spec.git.merge.option.--continue.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "rebase",
                        description = commandSpecText("spec.git.rebase.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-i", "--interactive"),
                                    commandSpecText("spec.git.rebase.option.-i.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--onto"),
                                    commandSpecText("spec.git.rebase.option.--onto.description"),
                                    requiresValue = true,
                                    valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                                ),
                                TerminalOptionSpec(
                                    listOf("--continue"),
                                    commandSpecText("spec.git.rebase.option.--continue.description"),
                                ),
                                TerminalOptionSpec(listOf("--abort"), commandSpecText("spec.git.rebase.option.--abort.description")),
                                TerminalOptionSpec(listOf("--skip"), commandSpecText("spec.git.rebase.option.--skip.description")),
                                TerminalOptionSpec(
                                    listOf("--autostash"),
                                    commandSpecText("spec.git.rebase.option.--autostash.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "reset",
                        description = commandSpecText("spec.git.reset.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--soft"), commandSpecText("spec.git.reset.option.--soft.description")),
                                TerminalOptionSpec(listOf("--mixed"), commandSpecText("spec.git.reset.option.--mixed.description")),
                                TerminalOptionSpec(listOf("--hard"), commandSpecText("spec.git.reset.option.--hard.description")),
                                TerminalOptionSpec(
                                    listOf("--merge"),
                                    commandSpecText("spec.git.reset.option.--merge.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("--keep"),
                                    commandSpecText("spec.git.reset.option.--keep.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "log",
                        description = commandSpecText("spec.git.log.description"),
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--oneline"), commandSpecText("spec.git.log.option.--oneline.description")),
                                TerminalOptionSpec(listOf("--graph"), commandSpecText("spec.git.log.option.--graph.description")),
                                TerminalOptionSpec(listOf("--stat"), commandSpecText("spec.git.log.option.--stat.description")),
                                TerminalOptionSpec(
                                    listOf("-n", "--max-count"),
                                    commandSpecText("spec.git.log.option.-n.description"),
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "show",
                        description = commandSpecText("spec.git.show.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_COMMIT,
                    ),
                    TerminalCommandSpec(
                        name = "diff",
                        description = commandSpecText("spec.git.diff.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("--staged", "--cached"),
                                    commandSpecText("spec.git.diff.option.--staged.description"),
                                ),
                                TerminalOptionSpec(listOf("--name-only"), commandSpecText("spec.git.diff.option.--name-only.description")),
                                TerminalOptionSpec(
                                    listOf("--name-status"),
                                    commandSpecText("spec.git.diff.option.--name-status.description"),
                                ),
                                TerminalOptionSpec(listOf("--stat"), commandSpecText("spec.git.diff.option.--stat.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "stash",
                        description = commandSpecText("spec.git.stash.description"),
                        subcommands =
                            listOf(
                                TerminalCommandSpec("push", commandSpecText("spec.git.stash.push.description")),
                                TerminalCommandSpec("pop", commandSpecText("spec.git.stash.pop.description")),
                                TerminalCommandSpec("apply", commandSpecText("spec.git.stash.apply.description")),
                                TerminalCommandSpec("list", commandSpecText("spec.git.stash.list.description")),
                                TerminalCommandSpec("show", commandSpecText("spec.git.stash.show.description")),
                                TerminalCommandSpec("drop", commandSpecText("spec.git.stash.drop.description")),
                                TerminalCommandSpec("clear", commandSpecText("spec.git.stash.clear.description")),
                                TerminalCommandSpec(
                                    "branch",
                                    commandSpecText("spec.git.stash.branch.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "remote",
                        description = commandSpecText("spec.git.remote.description"),
                        subcommands =
                            listOf(
                                TerminalCommandSpec("add", commandSpecText("spec.git.remote.add.description")),
                                TerminalCommandSpec("rename", commandSpecText("spec.git.remote.rename.description")),
                                TerminalCommandSpec("remove", commandSpecText("spec.git.remote.remove.description")),
                                TerminalCommandSpec("get-url", commandSpecText("spec.git.remote.get-url.description")),
                                TerminalCommandSpec("set-url", commandSpecText("spec.git.remote.set-url.description")),
                                TerminalCommandSpec("show", commandSpecText("spec.git.remote.show.description")),
                                TerminalCommandSpec("prune", commandSpecText("spec.git.remote.prune.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "tag",
                        description = commandSpecText("spec.git.tag.description"),
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-a", "--annotate"), commandSpecText("spec.git.tag.option.-a.description")),
                                TerminalOptionSpec(listOf("-d", "--delete"), commandSpecText("spec.git.tag.option.-d.description")),
                                TerminalOptionSpec(listOf("-l", "--list"), commandSpecText("spec.git.tag.option.-l.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "cherry-pick",
                        description = commandSpecText("spec.git.cherry-pick.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_COMMIT,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("--continue"),
                                    commandSpecText("spec.git.cherry-pick.option.--continue.description"),
                                ),
                                TerminalOptionSpec(listOf("--abort"), commandSpecText("spec.git.cherry-pick.option.--abort.description")),
                                TerminalOptionSpec(listOf("--skip"), commandSpecText("spec.git.cherry-pick.option.--skip.description")),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "revert",
                        description = commandSpecText("spec.git.revert.description"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_COMMIT,
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--continue"), commandSpecText("spec.git.revert.option.--continue.description")),
                                TerminalOptionSpec(listOf("--abort"), commandSpecText("spec.git.revert.option.--abort.description")),
                                TerminalOptionSpec(
                                    listOf("--no-commit", "-n"),
                                    commandSpecText("spec.git.revert.option.--no-commit.description"),
                                ),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.git.option.--help.description")),
                    TerminalOptionSpec(listOf("--version"), commandSpecText("spec.git.option.--version.description")),
                    TerminalOptionSpec(
                        names = listOf("-C"),
                        description = commandSpecText("spec.git.option.-C.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.DIRECTORY,
                    ),
                ),
        )
}
