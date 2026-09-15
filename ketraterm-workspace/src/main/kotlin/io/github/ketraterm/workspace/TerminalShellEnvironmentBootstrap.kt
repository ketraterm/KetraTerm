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
package io.github.ketraterm.workspace

/** The launch-only marker protocol shared by the supported shell startup hooks. */
internal object TerminalShellEnvironmentBootstrap {
    fun applyInitial(
        profile: TerminalProfile,
        inherited: Map<String, String> = System.getenv(),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    ): TerminalProfile {
        val requested = profile.shellEnvironment
        if (requested == TerminalShellEnvironment.Empty) return profile
        val environment = profile.environment.toMutableMap()

        fun setVariable(
            name: String,
            value: String,
        ) {
            val key = if (windows) inherited.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name else name
            if (windows) environment.keys.removeAll { it.equals(name, ignoreCase = true) }
            environment[key] = value
        }
        for ((name, value) in requested.variables) setVariable(name, value)
        requested.pathPrefix?.let { prefix ->
            val path =
                environment.entries.lastOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value
                    ?: inherited.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value
            val separator = if (windows) ';' else ':'
            setVariable("PATH", if (path.isNullOrEmpty()) prefix else "$prefix$separator$path")
        }
        return profile.copy(environment = environment)
    }

    fun withMarkers(profile: TerminalProfile): TerminalProfile {
        val requested = profile.shellEnvironment
        if (requested == TerminalShellEnvironment.Empty) return profile
        val environment = profile.environment.toMutableMap()
        for ((name, value) in requested.variables) environment["_KetraTerm_FORCE_SET_$name"] = value
        requested.pathPrefix?.let { environment["_KetraTerm_FORCE_PREPEND_PATH"] = it }
        return profile.copy(environment = environment)
    }

    val bash: String =
        """
        for __ketraterm_env_marker in ${'$'}{!_KetraTerm_FORCE_SET_@}; do
            __ketraterm_env_name=${'$'}{__ketraterm_env_marker#_KetraTerm_FORCE_SET_}
            export "${'$'}__ketraterm_env_name=${'$'}{!__ketraterm_env_marker}"
            unset "${'$'}__ketraterm_env_marker"
        done
        if [[ -n ${'$'}{_KetraTerm_FORCE_PREPEND_PATH:-} ]]; then
            __ketraterm_path_prefix=${'$'}_KetraTerm_FORCE_PREPEND_PATH
            if [[ -x /usr/bin/cygpath ]]; then
                __ketraterm_path_prefix=${'$'}(/usr/bin/cygpath -u -- "${'$'}__ketraterm_path_prefix")
            fi
            if [[ ${'$'}{PATH%%:*} != "${'$'}__ketraterm_path_prefix" ]]; then
                export PATH="${'$'}__ketraterm_path_prefix${'$'}{PATH:+:${'$'}PATH}"
            fi
        fi
        unset _KetraTerm_FORCE_PREPEND_PATH __ketraterm_env_marker __ketraterm_env_name __ketraterm_path_prefix
        """.trimIndent()

    val zsh: String =
        """
        builtin zmodload zsh/parameter 2>/dev/null
        for __ketraterm_env_marker in ${'$'}{parameters[(I)_KetraTerm_FORCE_SET_*]}; do
            __ketraterm_env_name=${'$'}{__ketraterm_env_marker#_KetraTerm_FORCE_SET_}
            export "${'$'}__ketraterm_env_name=${'$'}{(P)__ketraterm_env_marker}"
            unset "${'$'}__ketraterm_env_marker"
        done
        if [[ -n ${'$'}{_KetraTerm_FORCE_PREPEND_PATH:-} && ${'$'}{PATH%%:*} != "${'$'}_KetraTerm_FORCE_PREPEND_PATH" ]]; then
            export PATH="${'$'}_KetraTerm_FORCE_PREPEND_PATH${'$'}{PATH:+:${'$'}PATH}"
        fi
        unset _KetraTerm_FORCE_PREPEND_PATH __ketraterm_env_marker __ketraterm_env_name
        """.trimIndent()

    val fish: String =
        """
        for __ketraterm_env_marker in (set --names | string match '_KetraTerm_FORCE_SET_*')
            set -l __ketraterm_env_name (string replace '_KetraTerm_FORCE_SET_' '' -- ${'$'}__ketraterm_env_marker)
            if contains -- ${'$'}__ketraterm_env_name PATH CDPATH MANPATH
                set -gx ${'$'}__ketraterm_env_name (string split ':' -- "${'$'}${'$'}__ketraterm_env_marker")
            else
                set -gx ${'$'}__ketraterm_env_name "${'$'}${'$'}__ketraterm_env_marker"
            end
            set -e ${'$'}__ketraterm_env_marker
        end
        if set -q _KetraTerm_FORCE_PREPEND_PATH
            if not set -q PATH[1]; or test "${'$'}PATH[1]" != "${'$'}_KetraTerm_FORCE_PREPEND_PATH"
                set -gx PATH "${'$'}_KetraTerm_FORCE_PREPEND_PATH" ${'$'}PATH
            end
        end
        set -e _KetraTerm_FORCE_PREPEND_PATH
        set -e __ketraterm_env_marker
        """.trimIndent()

    val powerShell: String =
        """
        foreach (${'$'}__KetraTermMarker in @(Get-ChildItem Env: | Where-Object Name -Like '_KetraTerm_FORCE_SET_*')) {
            [Environment]::SetEnvironmentVariable(${'$'}__KetraTermMarker.Name.Substring(21), ${'$'}__KetraTermMarker.Value, 'Process')
            Remove-Item -LiteralPath ('Env:' + ${'$'}__KetraTermMarker.Name)
        }
        if (${'$'}env:_KetraTerm_FORCE_PREPEND_PATH) {
            ${'$'}__KetraTermSeparator = [IO.Path]::PathSeparator
            if ((${'$'}env:PATH -split [regex]::Escape([string]${'$'}__KetraTermSeparator), 2)[0] -cne ${'$'}env:_KetraTerm_FORCE_PREPEND_PATH) {
                ${'$'}env:PATH = ${'$'}env:_KetraTerm_FORCE_PREPEND_PATH + $(if (${'$'}env:PATH) { [string]${'$'}__KetraTermSeparator + ${'$'}env:PATH })
            }
        }
        Remove-Item Env:_KetraTerm_FORCE_PREPEND_PATH -ErrorAction SilentlyContinue
        Remove-Variable __KetraTermMarker, __KetraTermSeparator -ErrorAction SilentlyContinue
        """.trimIndent()
}
