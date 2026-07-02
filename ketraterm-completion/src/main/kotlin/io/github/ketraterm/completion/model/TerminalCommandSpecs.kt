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
            docker(),
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
        )

    private fun cat(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cat",
            description = "print file contents",
            aliases = listOf("type"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
        )

    private fun mkdir(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mkdir",
            description = "create directories",
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun rm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "rm",
            description = "remove files or directories",
            aliases = listOf("del", "erase"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
        )

    private fun cp(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cp",
            description = "copy files or directories",
            aliases = listOf("copy"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
        )

    private fun mv(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mv",
            description = "move files or directories",
            aliases = listOf("move"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
        )

    private fun code(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "code",
            description = "open files or directories in Visual Studio Code",
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
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
                    TerminalCommandSpec(
                        name = "status",
                        description = "show working tree status",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("--short", "-s"), "show status concisely"),
                                TerminalOptionSpec(listOf("--branch", "-b"), "show branch information"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "add",
                        description = "add file contents to the index",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec("commit", "record changes to the repository"),
                    TerminalCommandSpec(
                        name = "checkout",
                        description = "switch branches or restore files",
                        aliases = listOf("co"),
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                    ),
                    TerminalCommandSpec(
                        name = "switch",
                        description = "switch branches",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
                    ),
                    TerminalCommandSpec("branch", "list, create, or delete branches"),
                    TerminalCommandSpec("pull", "fetch from and integrate with another repository"),
                    TerminalCommandSpec("push", "update remote refs"),
                    TerminalCommandSpec("fetch", "download objects and refs"),
                    TerminalCommandSpec("merge", "join development histories"),
                    TerminalCommandSpec("rebase", "reapply commits on top of another base"),
                    TerminalCommandSpec("log", "show commit logs"),
                    TerminalCommandSpec(
                        name = "diff",
                        description = "show changes between commits, trees, or files",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec("stash", "stash local modifications"),
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
    @JvmStatic
    fun gradle(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "gradle",
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
                    TerminalCommandSpec(
                        name = "run",
                        description = "run a package script",
                        positionalArgumentValueDomain = TerminalCompletionValueDomain.NPM_SCRIPT,
                    ),
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
                    TerminalCommandSpec(
                        name = "compose",
                        description = "manage Compose applications",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("up", "create and start containers"),
                                TerminalCommandSpec("down", "stop and remove containers"),
                                TerminalCommandSpec("ps", "list containers"),
                                TerminalCommandSpec("logs", "view output from containers"),
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
     * Returns a Cargo command spec for Rust package management.
     *
     * @return Cargo command specification.
     */
    @JvmStatic
    fun cargo(): TerminalCommandSpec =
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
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(listOf("--verbose", "-v"), "use verbose output"),
                    TerminalOptionSpec(listOf("--quiet", "-q"), "do not print cargo log messages"),
                    TerminalOptionSpec(
                        names = listOf("--manifest-path"),
                        description = "path to Cargo.toml",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                ),
        )

    /**
     * Returns a kubectl command spec for Kubernetes cluster management.
     *
     * @return kubectl command specification.
     */
    @JvmStatic
    fun kubectl(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kubectl",
            description = "Kubernetes cluster CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("get", "display one or many resources"),
                    TerminalCommandSpec("describe", "show details of a specific resource or group of resources"),
                    TerminalCommandSpec("logs", "print the logs for a container in a pod"),
                    TerminalCommandSpec("exec", "execute a command in a container"),
                    TerminalCommandSpec(
                        name = "apply",
                        description = "apply a configuration to a resource by file name or stdin",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec(
                        name = "delete",
                        description = "delete resources by file names, stdin, resources and names",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                    ),
                    TerminalCommandSpec("port-forward", "forward one or more local ports to a pod"),
                    TerminalCommandSpec("config", "modify kubeconfig files"),
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
    @JvmStatic
    fun gh(): TerminalCommandSpec =
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
    @JvmStatic
    fun pip(): TerminalCommandSpec =
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
    @JvmStatic
    fun go(): TerminalCommandSpec =
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
    @JvmStatic
    fun aws(): TerminalCommandSpec =
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
    @JvmStatic
    fun ketra(): TerminalCommandSpec =
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
}
