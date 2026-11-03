#
# Copyright 2026 Gagik Sargsyan
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Use the same bounded inventory wire format as Bash. Inspect shell tables in a
# background snapshot; never invoke ZLE, compinit, user functions or completers.
function __ketraterm_zsh_publish_commands() {
    builtin local aliases_enabled=${2-${options[aliases]}}
    builtin emulate -L zsh
    builtin local LC_ALL=C name replacement kind expansion encoded executable
    builtin local -i size=0 count=0
    builtin local -a entries available
    builtin local -A seen
    builtin zmodload zsh/parameter || return
    executable=$(builtin whence -p base64) || return
    # Populate PATH names in this worker only. Validate cached paths below so a
    # removed executable cannot survive through the inherited command hash.
    builtin hash -f
    for name in "${reswords[@]}"; do seen[$name]=1; done
    if [[ $aliases_enabled == on ]]; then available+=("${(@ok)aliases}"); fi
    available+=("${(@ok)functions}" "${(@ok)builtins}" "${(@ok)commands}")
    for name in "${available[@]}"; do
        [[ -n $name && $name != __ketraterm_* && $name != *[$'\001'-$'\037'$'\177']* ]] || continue
        # UTF-8 C1 controls are also rejected by the inventory decoder.
        [[ $name != *$'\302'[$'\200'-$'\237']* ]] || continue
        [[ -z ${seen[$name]-} ]] || continue
        seen[$name]=1
        ((${#name} <= 512)) || continue
        kind=Command
        expansion=
        if [[ $aliases_enabled == on && ${+aliases[$name]} == 1 ]]; then
            kind=Alias
            expansion=${aliases[$name]}
            if ((${#expansion} > 4096)); then expansion=; fi
        elif [[ ${+functions[$name]} == 0 && ${+builtins[$name]} == 0 ]]; then
            [[ -x ${commands[$name]} && ! -d ${commands[$name]} ]] || continue
        fi
        replacement=$name
        # Leave ordinary aliases unquoted so zsh can expand them. Other names
        # use literal shell quoting when needed; no name or definition is eval'd.
        if [[ $replacement == *[^a-zA-Z0-9_./-]* ]]; then
            [[ $kind != Alias ]] || continue
            replacement=${(qq)name}
        fi
        size=$((size + ${#name} + ${#replacement} + ${#kind} + ${#expansion} + 4))
        ((size < 180000 && count < 8192)) || break
        entries+=("$name" "$replacement" "$kind" "$expansion")
        ((count+=1))
    done
    encoded=$(
        if ((count)); then builtin printf '%s\0' "${entries[@]}"; fi | command "$executable"
    ) || { builtin printf '\033]633;KetraTermCommands;2;Result;%s;Unavailable\a' "$1"; return; }
    encoded=${encoded//$'\n'/}
    encoded=${encoded//$'\r'/}
    builtin printf '\033]633;KetraTermCommands;2;Result;%s;%s\a' "$1" "$encoded"
}

function __ketraterm_zsh_completion_ready() {
    [[ -o interactive && -t 1 ]] || return
    builtin local aliases_enabled=${options[aliases]}
    builtin emulate -L zsh
    builtin zmodload zsh/parameter || return
    builtin whence -p base64 >/dev/null || return
    builtin local signature epoch name
    # Definitions matter for alias semantics, names for function availability.
    # Include enabled grammar and alias policy before starting the worker.
    signature=$(
        builtin printf '%q\n' "$PATH" "$PWD" "$aliases_enabled" "${(@ok)functions}" "${(@ok)builtins}" "${reswords[@]}"
        for name in "${(@ok)aliases}"; do builtin printf '%q:%q\n' "$name" "${aliases[$name]}"; done
    )
    if [[ $signature != "${__ketraterm_zsh_completion_signature-}" ]]; then
        builtin typeset -g __ketraterm_zsh_completion_signature=$signature
        builtin printf -v __ketraterm_zsh_completion_epoch '%04x%04x%04x%04x%04x%04x%04x%04x' \
            "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    epoch=$__ketraterm_zsh_completion_epoch
    builtin printf '\033]633;KetraTermCommands;2;Begin;%s\a' "$epoch"
    if [[ -n ${__ketraterm_zsh_completion_pid:-} ]] && builtin kill -0 "$__ketraterm_zsh_completion_pid" 2>/dev/null; then return; fi
    # Disown inside a subshell: no jobs, notifications, or changes to the user's $!.
    builtin typeset -g __ketraterm_zsh_completion_pid=$(
        __ketraterm_zsh_publish_commands "$epoch" "$aliases_enabled" </dev/null >/dev/tty 2>/dev/null &!
        builtin printf '%s' "$!"
    )
}
