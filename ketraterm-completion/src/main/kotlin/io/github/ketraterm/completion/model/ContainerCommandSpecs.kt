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
 * Curated container and orchestration specifications (Docker, Docker Compose, Kubectl).
 */
internal object ContainerCommandSpecs {
    fun docker(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker",
            description = commandSpecText("spec.docker.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("ps", commandSpecText("spec.docker.ps.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.docker.run.description")),
                    TerminalCommandSpec("exec", commandSpecText("spec.docker.exec.description")),
                    TerminalCommandSpec("build", commandSpecText("spec.docker.build.description")),
                    TerminalCommandSpec("images", commandSpecText("spec.docker.images.description")),
                    TerminalCommandSpec("pull", commandSpecText("spec.docker.pull.description")),
                    TerminalCommandSpec("push", commandSpecText("spec.docker.push.description")),
                    TerminalCommandSpec("stop", commandSpecText("spec.docker.stop.description")),
                    TerminalCommandSpec("start", commandSpecText("spec.docker.start.description")),
                    TerminalCommandSpec("restart", commandSpecText("spec.docker.restart.description")),
                    TerminalCommandSpec("rm", commandSpecText("spec.docker.rm.description")),
                    TerminalCommandSpec("rmi", commandSpecText("spec.docker.rmi.description")),
                    TerminalCommandSpec("logs", commandSpecText("spec.docker.logs.description")),
                    TerminalCommandSpec("inspect", commandSpecText("spec.docker.inspect.description")),
                    TerminalCommandSpec("network", commandSpecText("spec.docker.network.description")),
                    TerminalCommandSpec("volume", commandSpecText("spec.docker.volume.description")),
                    TerminalCommandSpec("system", commandSpecText("spec.docker.system.description")),
                    TerminalCommandSpec(
                        name = "compose",
                        description = commandSpecText("spec.docker.compose.description"),
                        subcommands =
                            listOf(
                                TerminalCommandSpec("up", commandSpecText("spec.docker.compose.up.description")),
                                TerminalCommandSpec("down", commandSpecText("spec.docker.compose.down.description")),
                                TerminalCommandSpec("ps", commandSpecText("spec.docker.compose.ps.description")),
                                TerminalCommandSpec("logs", commandSpecText("spec.docker.compose.logs.description")),
                                TerminalCommandSpec("build", commandSpecText("spec.docker.compose.build.description")),
                                TerminalCommandSpec("exec", commandSpecText("spec.docker.compose.exec.description")),
                                TerminalCommandSpec("run", commandSpecText("spec.docker.compose.run.description")),
                                TerminalCommandSpec("restart", commandSpecText("spec.docker.compose.restart.description")),
                                TerminalCommandSpec("stop", commandSpecText("spec.docker.compose.stop.description")),
                                TerminalCommandSpec("start", commandSpecText("spec.docker.compose.start.description")),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), commandSpecText("spec.docker.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.docker.option.--version.description")),
                    TerminalOptionSpec(
                        names = listOf("--context"),
                        description = commandSpecText("spec.docker.option.--context.description"),
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.DOCKER_CONTEXT,
                    ),
                ),
        )

    fun dockerCompose(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker-compose",
            description = commandSpecText("spec.docker-compose.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec("up", commandSpecText("spec.docker-compose.up.description")),
                    TerminalCommandSpec("down", commandSpecText("spec.docker-compose.down.description")),
                    TerminalCommandSpec("ps", commandSpecText("spec.docker-compose.ps.description")),
                    TerminalCommandSpec("logs", commandSpecText("spec.docker-compose.logs.description")),
                    TerminalCommandSpec("build", commandSpecText("spec.docker-compose.build.description")),
                    TerminalCommandSpec("exec", commandSpecText("spec.docker-compose.exec.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.docker-compose.run.description")),
                    TerminalCommandSpec("restart", commandSpecText("spec.docker-compose.restart.description")),
                    TerminalCommandSpec("stop", commandSpecText("spec.docker-compose.stop.description")),
                    TerminalCommandSpec("start", commandSpecText("spec.docker-compose.start.description")),
                    TerminalCommandSpec("config", commandSpecText("spec.docker-compose.config.description")),
                    TerminalCommandSpec("pull", commandSpecText("spec.docker-compose.pull.description")),
                    TerminalCommandSpec("push", commandSpecText("spec.docker-compose.push.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), commandSpecText("spec.docker-compose.option.--help.description")),
                    TerminalOptionSpec(listOf("--version", "-v"), commandSpecText("spec.docker-compose.option.--version.description")),
                    TerminalOptionSpec(
                        listOf("-f", "--file"),
                        commandSpecText("spec.docker-compose.option.-f.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        listOf("-p", "--project-name"),
                        commandSpecText("spec.docker-compose.option.-p.description"),
                        requiresValue = true,
                    ),
                    TerminalOptionSpec(listOf("-d", "--detach"), commandSpecText("spec.docker-compose.option.-d.description")),
                    TerminalOptionSpec(listOf("--build"), commandSpecText("spec.docker-compose.option.--build.description")),
                    TerminalOptionSpec(
                        listOf("--remove-orphans"),
                        commandSpecText("spec.docker-compose.option.--remove-orphans.description"),
                    ),
                ),
        )

    fun kubectl(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kubectl",
            description = commandSpecText("spec.kubectl.description"),
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "get",
                        description = commandSpecText("spec.kubectl.get.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = commandSpecText("spec.kubectl.get.argument.resource.name"),
                                    description = commandSpecText("spec.kubectl.get.argument.resource.description"),
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("-o", "--output"),
                                    description = commandSpecText("spec.kubectl.get.option.-o.description"),
                                    requiresValue = true,
                                    valueCandidates = listOf("yaml", "json", "wide", "name"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-A", "--all-namespaces"),
                                    commandSpecText("spec.kubectl.get.option.-A.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-l", "--selector"),
                                    commandSpecText("spec.kubectl.get.option.-l.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(
                                    listOf("-w", "--watch"),
                                    commandSpecText("spec.kubectl.get.option.-w.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "describe",
                        description = commandSpecText("spec.kubectl.describe.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = commandSpecText("spec.kubectl.describe.argument.resource.name"),
                                    description = commandSpecText("spec.kubectl.describe.argument.resource.description"),
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-A", "--all-namespaces"),
                                    commandSpecText("spec.kubectl.describe.option.-A.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-l", "--selector"),
                                    commandSpecText("spec.kubectl.describe.option.-l.description"),
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "logs",
                        description = commandSpecText("spec.kubectl.logs.description"),
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-f", "--follow"), commandSpecText("spec.kubectl.logs.option.-f.description")),
                                TerminalOptionSpec(
                                    listOf("-p", "--previous"),
                                    commandSpecText("spec.kubectl.logs.option.-p.description"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-c", "--container"),
                                    commandSpecText("spec.kubectl.logs.option.-c.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(
                                    listOf("--tail"),
                                    commandSpecText("spec.kubectl.logs.option.--tail.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(
                                    listOf("--timestamps"),
                                    commandSpecText("spec.kubectl.logs.option.--timestamps.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "exec",
                        description = commandSpecText("spec.kubectl.exec.description"),
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-i", "--stdin"), commandSpecText("spec.kubectl.exec.option.-i.description")),
                                TerminalOptionSpec(listOf("-t", "--tty"), commandSpecText("spec.kubectl.exec.option.-t.description")),
                                TerminalOptionSpec(
                                    listOf("-c", "--container"),
                                    commandSpecText("spec.kubectl.exec.option.-c.description"),
                                    requiresValue = true,
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "apply",
                        description = commandSpecText("spec.kubectl.apply.description"),
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-f", "--filename"),
                                    commandSpecText("spec.kubectl.apply.option.-f.description"),
                                    requiresValue = true,
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    listOf("-R", "--recursive"),
                                    commandSpecText("spec.kubectl.apply.option.-R.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "delete",
                        description = commandSpecText("spec.kubectl.delete.description"),
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = commandSpecText("spec.kubectl.delete.argument.resource.name"),
                                    description = commandSpecText("spec.kubectl.delete.argument.resource.description"),
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-f", "--filename"),
                                    commandSpecText("spec.kubectl.delete.option.-f.description"),
                                    requiresValue = true,
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(
                                    listOf("-l", "--selector"),
                                    commandSpecText("spec.kubectl.delete.option.-l.description"),
                                    requiresValue = true,
                                ),
                                TerminalOptionSpec(
                                    listOf("--force"),
                                    commandSpecText("spec.kubectl.delete.option.--force.description"),
                                ),
                            ),
                    ),
                    TerminalCommandSpec("port-forward", commandSpecText("spec.kubectl.port-forward.description")),
                    TerminalCommandSpec("config", commandSpecText("spec.kubectl.config.description")),
                    TerminalCommandSpec("create", commandSpecText("spec.kubectl.create.description")),
                    TerminalCommandSpec("edit", commandSpecText("spec.kubectl.edit.description")),
                    TerminalCommandSpec("top", commandSpecText("spec.kubectl.top.description")),
                    TerminalCommandSpec("rollout", commandSpecText("spec.kubectl.rollout.description")),
                    TerminalCommandSpec("scale", commandSpecText("spec.kubectl.scale.description")),
                    TerminalCommandSpec("drain", commandSpecText("spec.kubectl.drain.description")),
                    TerminalCommandSpec("cordon", commandSpecText("spec.kubectl.cordon.description")),
                    TerminalCommandSpec("uncordon", commandSpecText("spec.kubectl.uncordon.description")),
                    TerminalCommandSpec("run", commandSpecText("spec.kubectl.run.description")),
                    TerminalCommandSpec("explain", commandSpecText("spec.kubectl.explain.description")),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), commandSpecText("spec.kubectl.option.--help.description")),
                    TerminalOptionSpec(
                        names = listOf("--kubeconfig"),
                        description = commandSpecText("spec.kubectl.option.--kubeconfig.description"),
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--namespace", "-n"),
                        description = commandSpecText("spec.kubectl.option.--namespace.description"),
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--context"),
                        description = commandSpecText("spec.kubectl.option.--context.description"),
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.KUBERNETES_CONTEXT,
                    ),
                ),
        )

    internal val KUBECTL_RESOURCES =
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

internal val KUBECTL_RESOURCES: List<String>
    get() = ContainerCommandSpecs.KUBECTL_RESOURCES
