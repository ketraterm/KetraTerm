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

import java.util.*
import java.util.List.copyOf

/**
 * Curated static command specs useful as a bootstrap source before richer
 * imported corpora and host context providers are available.
 */
public object TerminalCommandSpecs {
    private val DEFAULT_CATALOG: List<TerminalCommandSpec> =
        freezeSpecs(
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
                GitCommandSpecs.git(),
                GradleCommandSpecs.gradle(),
                PackageManagerCommandSpecs.npm(),
                PackageManagerCommandSpecs.pnpm(),
                PackageManagerCommandSpecs.yarn(),
                PackageManagerCommandSpecs.bun(),
                ContainerCommandSpecs.docker(),
                ContainerCommandSpecs.dockerCompose(),
                PackageManagerCommandSpecs.cargo(),
                ContainerCommandSpecs.kubectl(),
                PackageManagerCommandSpecs.gh(),
                PackageManagerCommandSpecs.pip(),
                PackageManagerCommandSpecs.go(),
                ToolchainCommandSpecs.aws(),
                ToolchainCommandSpecs.kotlin(),
                ToolchainCommandSpecs.kotlinc(),
                ToolchainCommandSpecs.adb(),
                ToolchainCommandSpecs.ketra(),
            ),
        )

    /**
     * Returns the shared immutable default spec catalog for common developer commands.
     *
     * @return built-in command specifications.
     */
    @JvmStatic
    public fun defaults(): List<TerminalCommandSpec> = DEFAULT_CATALOG

    /**
     * Builds an immutable catalog localized for [locale], with English fallback.
     *
     * Catalog construction is startup work; completion requests perform no
     * bundle lookups. Canonical tokens, aliases, options, value candidates,
     * semantic metadata, and catalog order remain unchanged.
     */
    @JvmStatic
    public fun defaults(locale: Locale): List<TerminalCommandSpec> =
        defaults(
            ResourceBundle.getBundle(
                SPEC_BUNDLE_NAME,
                locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
            ),
        )

    /** Builds a localized catalog from [bundle], preserving English for missing keys. */
    @JvmStatic
    public fun defaults(bundle: ResourceBundle): List<TerminalCommandSpec> =
        defaults { key, english -> if (bundle.containsKey(key)) bundle.getString(key) else english }

    /**
     * Builds a localized immutable catalog using a host's existing message system.
     *
     * [localize] receives a stable key and the English fallback for each nonempty
     * description and argument label. Keys are `spec.<canonical.path>.description`,
     * `spec.<canonical.path>.option.<first-option-token>.description`, and
     * `spec.<canonical.path>.argument.<original-name>.name` or `.description`.
     * For example: `spec.git.status.description`,
     * `spec.git.status.option.--short.description`, and
     * `spec.kubectl.get.argument.resource.name`. The argument's zero-based ordinal
     * replaces its name when that name is empty. Dots separate canonical command
     * and subcommand tokens; aliases and translated labels never become keys.
     */
    @JvmStatic
    public fun defaults(localize: (key: String, english: String) -> String): List<TerminalCommandSpec> =
        freezeSpecs(localizeSpecs(DEFAULT_CATALOG, "", localize))

    private fun localizeSpecs(
        specs: List<TerminalCommandSpec>,
        parentPath: String,
        localize: (String, String) -> String,
    ): List<TerminalCommandSpec> =
        specs.map { spec ->
            val path = if (parentPath.isEmpty()) spec.name else "$parentPath.${spec.name}"
            val key = "spec.$path"
            spec.copy(
                description = localizeNonempty("$key.description", spec.description, localize),
                subcommands = localizeSpecs(spec.subcommands, path, localize),
                options =
                    spec.options.map { option ->
                        option.copy(
                            description = localizeNonempty("$key.option.${option.names.first()}.description", option.description, localize),
                        )
                    },
                positionalArguments =
                    spec.positionalArguments.mapIndexed { index, argument ->
                        val argumentKey = "$key.argument.${argument.name.ifEmpty { index.toString() }}"
                        argument.copy(
                            name = localizeNonempty("$argumentKey.name", argument.name, localize),
                            description = localizeNonempty("$argumentKey.description", argument.description, localize),
                        )
                    },
            )
        }

    private fun localizeNonempty(
        key: String,
        english: String,
        localize: (String, String) -> String,
    ): String = if (english.isEmpty()) english else localize(key, english)

    private fun cd(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cd",
            description = commandSpecText("spec.cd.description"),
            aliases = listOf("chdir", "sl", "set-location"),
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun pushd(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "pushd",
            description = commandSpecText("spec.pushd.description"),
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
        )

    private fun ls(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "ls",
            description = commandSpecText("spec.ls.description"),
            aliases = listOf("dir"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-l"), commandSpecText("spec.ls.option.-l.description")),
                    TerminalOptionSpec(listOf("-a", "--all"), commandSpecText("spec.ls.option.-a.description")),
                    TerminalOptionSpec(listOf("-h", "--human-readable"), commandSpecText("spec.ls.option.-h.description")),
                    TerminalOptionSpec(listOf("-t"), commandSpecText("spec.ls.option.-t.description")),
                    TerminalOptionSpec(listOf("-r", "--reverse"), commandSpecText("spec.ls.option.-r.description")),
                ),
        )

    private fun cat(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cat",
            description = commandSpecText("spec.cat.description"),
            aliases = listOf("type"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-n", "--number"), commandSpecText("spec.cat.option.-n.description")),
                ),
        )

    private fun mkdir(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mkdir",
            description = commandSpecText("spec.mkdir.description"),
            positionalArgumentPathKind = TerminalPathArgumentKind.DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-p", "--parents"), commandSpecText("spec.mkdir.option.-p.description")),
                ),
        )

    private fun rm(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "rm",
            description = commandSpecText("spec.rm.description"),
            aliases = listOf("del", "erase"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "-R", "--recursive"), commandSpecText("spec.rm.option.-r.description")),
                    TerminalOptionSpec(listOf("-f", "--force"), commandSpecText("spec.rm.option.-f.description")),
                ),
        )

    private fun cp(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "cp",
            description = commandSpecText("spec.cp.description"),
            aliases = listOf("copy"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "-R", "--recursive"), commandSpecText("spec.cp.option.-r.description")),
                    TerminalOptionSpec(
                        listOf("-f", "--force"),
                        commandSpecText("spec.cp.option.-f.description"),
                    ),
                ),
        )

    private fun mv(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "mv",
            description = commandSpecText("spec.mv.description"),
            aliases = listOf("move"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-f", "--force"), commandSpecText("spec.mv.option.-f.description")),
                ),
        )

    private fun code(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "code",
            description = commandSpecText("spec.code.description"),
            positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
            options =
                listOf(
                    TerminalOptionSpec(listOf("-r", "--reuse-window"), commandSpecText("spec.code.option.-r.description")),
                    TerminalOptionSpec(listOf("-n", "--new-window"), commandSpecText("spec.code.option.-n.description")),
                    TerminalOptionSpec(listOf("-g", "--goto"), commandSpecText("spec.code.option.-g.description")),
                    TerminalOptionSpec(listOf("-d", "--diff"), commandSpecText("spec.code.option.-d.description")),
                    TerminalOptionSpec(listOf("-w", "--wait"), commandSpecText("spec.code.option.-w.description")),
                ),
        )

    internal val KUBECTL_RESOURCES: List<String>
        get() = ContainerCommandSpecs.KUBECTL_RESOURCES

    private fun freezeSpecs(specs: List<TerminalCommandSpec>): List<TerminalCommandSpec> =
        immutableList(
            specs.map { spec ->
                spec.copy(
                    aliases = immutableList(spec.aliases),
                    subcommands = freezeSpecs(spec.subcommands),
                    options =
                        immutableList(
                            spec.options.map { option ->
                                option.copy(
                                    names = immutableList(option.names),
                                    valueCandidates = immutableList(option.valueCandidates),
                                    exclusiveGroupIds = immutableList(option.exclusiveGroupIds),
                                )
                            },
                        ),
                    positionalArguments =
                        immutableList(
                            spec.positionalArguments.map { argument ->
                                argument.copy(valueCandidates = immutableList(argument.valueCandidates))
                            },
                        ),
                )
            },
        )

    private fun <T> immutableList(values: List<T>): List<T> = if (values.isEmpty()) emptyList() else copyOf(values)
}

private const val SPEC_BUNDLE_NAME = "io.github.ketraterm.completion.model.CommandSpecMessages"
private val ENGLISH_SPEC_MESSAGES: ResourceBundle = ResourceBundle.getBundle(SPEC_BUNDLE_NAME, Locale.ROOT)

/** Resolves the immutable English bootstrap catalog while the catalog is constructed. */
internal fun commandSpecText(key: String): String = ENGLISH_SPEC_MESSAGES.getString(key)
