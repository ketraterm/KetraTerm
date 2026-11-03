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
# prompt snapshot, with PATH discovery in the background. Never invoke ZLE,
# compinit, user functions or completers.
__ketraterm_zsh_completion_base64=$(builtin whence -p base64 || :)
__ketraterm_zsh_completion_mv=$(builtin whence -p mv || :)
function __ketraterm_zsh_flush_commands() {
    builtin local encoded
    ((chunk_size)) || return 0
    encoded=$(builtin printf '%s\0' "${entries[@]}" | command "$executable") || return 1
    encoded=${encoded//$'\n'/}
    encoded=${encoded//$'\r'/}
    builtin printf '\033]633;%s;3;Chunk;%s;%s;%s;%s\a' "$inventory_name" "$epoch" "$scan" "$chunks" "$encoded"
    entries=()
    chunk_size=0
    ((chunks+=1))
}
function __ketraterm_zsh_publish_commands() {
    builtin local aliases_enabled=${4-${options[aliases]}}
    builtin emulate -L zsh
    builtin local LC_ALL=C name replacement kind expansion executable epoch=$1 scan=${2:-1}
    builtin local inventory_kind=${3:-Path} inventory_name=KetraTermCommands
    [[ $inventory_kind != Local ]] || inventory_name=KetraTermShellCommands
    builtin local -i size=0 count=0 chunk_size=0 chunks=0 record_size=0 text_size=0
    builtin local -a entries available
    builtin local -A discovered seen
    builtin zmodload zsh/parameter || return
    executable=$__ketraterm_zsh_completion_base64
    [[ -n $executable ]] || return
    builtin printf '\033]633;%s;3;Begin;%s;%s\a' "$inventory_name" "$epoch" "$scan"
    for name in "${reswords[@]}"; do seen[$name]=1; done
    if [[ $inventory_kind == Local ]]; then
        if [[ $aliases_enabled == on ]]; then available+=("${(@ok)aliases}"); fi
        available+=("${(@ok)functions}" "${(@ok)builtins}")
    else
        available=("${(@0)$(__ketraterm_collect_path_commands)}")
        if [[ ${available[-1]-} == __ketraterm_inventory_overflow ]]; then
            builtin printf '\033]633;%s;3;Fail;%s;%s;Overflow\a' "$inventory_name" "$epoch" "$scan"
            return
        elif [[ ${available[-1]-} != __ketraterm_inventory_end ]]; then
            builtin printf '\033]633;%s;3;Fail;%s;%s;Unavailable\a' "$inventory_name" "$epoch" "$scan"
            return
        fi
        available[-1]=()
        for name in "${available[@]}"; do [[ -z $name ]] || discovered[$name]=1; done
        # PATH is already scanned. Inspect the existing hash table without
        # triggering another whole-PATH enumeration through $commands.
        builtin unsetopt HASH_LIST_ALL
        # Keep explicitly hashed commands outside PATH without rechecking every
        # normally discovered executable through its full absolute path.
        for name in "${(@ok)commands}"; do
            if [[ -z ${discovered[$name]-} && -x ${commands[$name]} && ! -d ${commands[$name]} ]]; then
                available+=("$name")
            fi
        done
    fi
    for name in "${available[@]}"; do
        [[ -n $name && $name != __ketraterm_* && $name != *[$'\001'-$'\037'$'\177']* ]] || continue
        # UTF-8 C1 controls are also rejected by the inventory decoder.
        [[ $name != *$'\302'[$'\200'-$'\237']* ]] || continue
        [[ -z ${seen[$name]-} ]] || continue
        seen[$name]=1
        ((${#name} <= 512)) || continue
        kind=C
        expansion=
        if [[ $inventory_kind == Local && $aliases_enabled == on && ${+aliases[$name]} == 1 ]]; then
            kind=A
            expansion=${aliases[$name]}
            if ((${#expansion} > 4096)); then expansion=; fi

        fi
        replacement=$name
        # Leave ordinary aliases unquoted so zsh can expand them. Other names
        # use literal shell quoting when needed; no name or definition is eval'd.
        if [[ $replacement == *[^a-zA-Z0-9_./-]* ]]; then
            [[ $kind != A ]] || continue
            replacement=${(qq)name}
        fi
        [[ $replacement != "$name" ]] || replacement=
        record_size=$((${#name} + ${#replacement} + ${#expansion} + 5))
        size=$((size + record_size))
        text_size=$((text_size + record_size - 5))
        if ((size > 4194304 || text_size > 2097152 || count >= 32768)); then
            builtin printf '\033]633;%s;3;Fail;%s;%s;Overflow\a' "$inventory_name" "$epoch" "$scan"
            return
        fi
        if ((chunk_size + record_size > 49152)); then
            if ! __ketraterm_zsh_flush_commands; then
                builtin printf '\033]633;%s;3;Fail;%s;%s;Unavailable\a' "$inventory_name" "$epoch" "$scan"
                return
            fi
        fi
        entries+=("$name" "$replacement" "$kind" "$expansion")
        ((chunk_size+=record_size))
        ((count+=1))
    done
    if ! __ketraterm_zsh_flush_commands; then
        builtin printf '\033]633;%s;3;Fail;%s;%s;Unavailable\a' "$inventory_name" "$epoch" "$scan"
        return
    fi
    builtin printf '\033]633;%s;3;End;%s;%s;%s;%s;%s\a' "$inventory_name" "$epoch" "$scan" "$chunks" "$count" "$size"
}

function __ketraterm_zsh_completion_ready() {
    [[ -o interactive && -t 1 ]] || return
    builtin local aliases_enabled=${options[aliases]}
    builtin emulate -L zsh
    builtin zmodload zsh/parameter || return
    [[ -n $__ketraterm_zsh_completion_base64 ]] || return
    [[ -n ${KetraTerm_COMMAND_INVENTORY_DIRECTORY-} && -d $KetraTerm_COMMAND_INVENTORY_DIRECTORY && -n $__ketraterm_zsh_completion_mv ]] || return
    builtin local signature name path_signature
    # Absolute PATH entries are independent of the working directory.
    builtin printf -v path_signature '%q' "$PATH"
    if [[ ":$PATH:" == *::* || ":$PATH:" == *:[!/]* ]]; then
        builtin printf -v path_signature '%s:%q' "$path_signature" "$PWD"
    fi
    signature=$(
        builtin printf '%q\n' "$aliases_enabled" "${(@ok)functions}" "${(@ok)builtins}" "${reswords[@]}"
        for name in "${(@ok)aliases}"; do builtin printf '%q:%q\n' "$name" "${aliases[$name]}"; done
    )
    if [[ $signature != "${__ketraterm_zsh_completion_signature-}" ]]; then
        builtin typeset -g __ketraterm_zsh_completion_signature=$signature
        builtin printf -v __ketraterm_zsh_completion_epoch '%04x%04x%04x%04x%04x%04x%04x%04x' "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    if [[ $path_signature != "${__ketraterm_zsh_completion_path_signature-}" ]]; then
        builtin typeset -g __ketraterm_zsh_completion_path_signature=$path_signature
        builtin printf -v __ketraterm_zsh_completion_path_epoch '%04x%04x%04x%04x%04x%04x%04x%04x' "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    builtin printf '\033]633;KetraTermShellCommands;3;Ready;%s\a' "$__ketraterm_zsh_completion_epoch"
    builtin printf '\033]633;KetraTermCommands;3;Ready;%s\a' "$__ketraterm_zsh_completion_path_epoch"
    # Publish bounded shell tables without waiting for filesystem discovery.
    # No completer, user function, command lookup or PATH walk runs here.
    if [[ ${__ketraterm_zsh_completion_scan_epoch-} != "$__ketraterm_zsh_completion_epoch" ]]; then
        builtin typeset -g __ketraterm_zsh_completion_scan_id=$((${__ketraterm_zsh_completion_scan_id:-0} + 1))
        __ketraterm_zsh_publish_commands "$__ketraterm_zsh_completion_epoch" "$__ketraterm_zsh_completion_scan_id" Local "$aliases_enabled"
        builtin typeset -g __ketraterm_zsh_completion_scan_epoch=$__ketraterm_zsh_completion_epoch
    fi
    if [[ -n ${__ketraterm_zsh_completion_pid:-} ]]; then
        builtin kill -0 "$__ketraterm_zsh_completion_pid" 2>/dev/null && return
        builtin typeset -g __ketraterm_zsh_completion_pid=
        builtin typeset -g __ketraterm_zsh_completion_scan_time=$SECONDS
    fi
    if [[ ${__ketraterm_zsh_completion_path_scan_epoch-} == "$__ketraterm_zsh_completion_path_epoch" ]] && ((SECONDS - ${__ketraterm_zsh_completion_scan_time:-0} < 60)); then return; fi
    builtin typeset -g __ketraterm_zsh_completion_path_scan_epoch=$__ketraterm_zsh_completion_path_epoch
    builtin typeset -g __ketraterm_zsh_completion_scan_id=$((${__ketraterm_zsh_completion_scan_id:-0} + 1))
    # Only PATH discovery runs in the background; never accumulate workers.
    builtin typeset -g __ketraterm_zsh_completion_pid=$(
        # The worker must never write to the live terminal: even one printf
        # can be split and interleave with prompt/redraw bytes in the PTY.
        (
            __ketraterm_zsh_publish_commands "$__ketraterm_zsh_completion_path_epoch" "$__ketraterm_zsh_completion_scan_id" Path "$aliases_enabled" > "$KetraTerm_COMMAND_INVENTORY_DIRECTORY/path.pending" &&
                command "$__ketraterm_zsh_completion_mv" -f -- "$KetraTerm_COMMAND_INVENTORY_DIRECTORY/path.pending" "$KetraTerm_COMMAND_INVENTORY_DIRECTORY/path.ready"
        ) </dev/null >/dev/null 2>&1 &!
        builtin printf '%s' "$!"
    )
}
