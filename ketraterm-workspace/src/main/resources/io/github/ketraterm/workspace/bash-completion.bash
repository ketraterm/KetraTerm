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

# Publish a prompt-scoped command inventory without invoking Readline: bind -x
# clears/redraws the editable line, which invalidates conditional-edit contexts.
# Argument completion remains with the catalog, host sources and normal Tab.
__ketraterm_completion_base64=$(builtin type -P base64 || :)
__ketraterm_completion_mv=$(builtin type -P mv || :)
# Flush the publisher's dynamically scoped chunk. Never retain an encoded snapshot.
__ketraterm_flush_commands() {
    local encoded
    ((chunk_size)) || return 0
    encoded=$(printf '%s\0' "${entries[@]}" | command "$__ketraterm_completion_base64") || return 1
    encoded=${encoded//$'\n'/}
    encoded=${encoded//$'\r'/}
    printf '\033]633;%s;3;Chunk;%s;%s;%s;%s\a' "$inventory_name" "$epoch" "$scan" "$chunks" "$encoded"
    entries=()
    chunk_size=0
    ((chunks+=1))
}
__ketraterm_publish_commands() {
    if [[ -z $__ketraterm_completion_base64 ]]; then return; fi
    local LC_ALL=C name replacement size=0 count=0 bare resolved REPLY kind expansion
    local epoch=$1 scan=${2:-1} chunk_size=0 chunks=0 record_size text_size=0
    local inventory_kind=${3:-Path} inventory_name=KetraTermCommands ascii_names=1
    [[ $inventory_kind != Local ]] || inventory_name=KetraTermShellCommands
    local -a entries=() available=() keywords=()
    local -A seen=() locals=() discovered=() folded=()
    printf '\033]633;%s;3;Begin;%s;%s\a' "$inventory_name" "$epoch" "$scan"
    # Reserved grammar tokens are not executable command names; quoting them
    # (for example '!') would turn valid shell syntax into a missing command.
    builtin compgen -V keywords -A keyword
    for name in "${keywords[@]}"; do seen["$name"]=1; done
    # Bash 5.3's array output preserves names containing whitespace without
    # spawning another shell or resolving every executable through PATH again.
    builtin compgen -V available -A alias -A function -A enabled
    for name in "${available[@]}"; do locals["$name"]=1; done
    if [[ $inventory_kind == Path ]]; then
        builtin mapfile -d '' -t available < <(__ketraterm_collect_path_commands)
        ((${#available[@]})) || available=(__ketraterm_inventory_unavailable)
        if [[ ${available[-1]-} == __ketraterm_inventory_overflow ]]; then
            builtin printf '\033]633;%s;3;Fail;%s;%s;Overflow\a' "$inventory_name" "$epoch" "$scan"
            return
        elif [[ ${available[-1]-} != __ketraterm_inventory_end ]]; then
            builtin printf '\033]633;%s;3;Fail;%s;%s;Unavailable\a' "$inventory_name" "$epoch" "$scan"
            return
        fi
        builtin unset 'available[-1]'
        # Preserve explicit hashes outside PATH; normal entries were validated
        # relative to their directory and do not need another PATH lookup.
        for name in "${available[@]}"; do discovered["$name"]=1; done
        for name in "${!BASH_CMDS[@]}"; do
            if [[ -z ${discovered[$name]-} && -x ${BASH_CMDS[$name]} && ! -d ${BASH_CMDS[$name]} ]]; then
                available+=("$name")
                discovered["$name"]=1
            fi
        done
    fi
    if [[ $inventory_kind == Path && ($OSTYPE == cygwin* || $OSTYPE == msys*) ]]; then
        for name in "${available[@]}"; do
            folded["${name,,}"]=$((${folded["${name,,}"]:-0} + 1))
            [[ $name != *[!$'\001'-$'\177']* ]] || ascii_names=0
        done
    fi
    for name in "${available[@]}"; do
        [[ -n $name && $name != __ketraterm_* && $name != *[$'\001'-$'\037'$'\177']* ]] || continue
        [[ $name != *$'\302'[$'\200'-$'\237']* ]] || continue
        [[ -z ${seen["$name"]+present} ]] || continue
        seen["$name"]=1
        ((${#name} <= 512)) || continue
        replacement=$name
        if [[ $inventory_kind == Path && ($OSTYPE == cygwin* || $OSTYPE == msys*) && $name == ?*.exe ]]; then
            bare=${name%.exe}
            # A unique ASCII spelling cannot resolve to a different file. All
            # case collisions, non-ASCII namespaces and shell hashes use Bash.
            if [[ $ascii_names == 1 && ${folded[${name,,}]} == 1 &&
                -z ${locals[$bare]-} && -z ${locals[$name]-} &&
                -z ${folded[${bare,,}]-} && -z ${BASH_CMDS[$bare]-} && -z ${BASH_CMDS[$name]-} ]]; then
                replacement=$bare
            else
                __ketraterm_resolve_command "$bare"
                resolved=$REPLY
                __ketraterm_resolve_command "$name"
                if [[ -n $resolved && -n $REPLY && $resolved -ef $REPLY ]]; then replacement=$bare; fi
            fi
        fi
        if [[ $replacement == *[^a-zA-Z0-9_./-]* ]]; then replacement="'${replacement//\'/\'\\\'\'}'"; fi
        kind=C
        expansion=
        if [[ $inventory_kind == Local && ${BASH_ALIASES[$name]+present} ]]; then
            kind=A
            expansion=${BASH_ALIASES[$name]}
            # Retain alias identity even when its definition exceeds the metadata budget.
            if ((${#expansion} > 4096)); then expansion=; fi
        fi
        [[ $replacement != "$name" ]] || replacement=
        record_size=$((${#name} + ${#replacement} + ${#expansion} + 5))
        size=$((size + record_size))
        text_size=$((text_size + record_size - 5))
        if ((size > 4194304 || text_size > 2097152 || count >= 32768)); then
            printf '\033]633;%s;3;Fail;%s;%s;Overflow\a' "$inventory_name" "$epoch" "$scan"
            return
        fi
        if ((chunk_size + record_size > 49152)); then
            if ! __ketraterm_flush_commands; then
                printf '\033]633;%s;3;Fail;%s;%s;Unavailable\a' "$inventory_name" "$epoch" "$scan"
                return
            fi
        fi
        entries+=("$name" "$replacement" "$kind" "$expansion")
        ((chunk_size+=record_size))
        ((count+=1))
    done
    if ! __ketraterm_flush_commands; then
        printf '\033]633;%s;3;Fail;%s;%s;Unavailable\a' "$inventory_name" "$epoch" "$scan"
        return
    fi
    printf '\033]633;%s;3;End;%s;%s;%s;%s;%s\a' "$inventory_name" "$epoch" "$scan" "$chunks" "$count" "$size"
}

__ketraterm_completion_ready() {
    [[ $- == *i* && -t 1 ]] || return
    [[ -n $__ketraterm_completion_base64 ]] || return
    [[ -n ${KetraTerm_COMMAND_INVENTORY_DIRECTORY-} && -d $KetraTerm_COMMAND_INVENTORY_DIRECTORY && -n $__ketraterm_completion_mv ]] || return
    local signature name path_signature
    # Absolute PATH entries are independent of the working directory.
    builtin printf -v path_signature '%q' "$PATH"
    if [[ ":$PATH:" == *::* || ":$PATH:" == *:[!/]* ]]; then
        builtin printf -v path_signature '%s:%q' "$path_signature" "$PWD"
    fi
    signature=$(
        declare -p BASH_ALIASES; builtin compgen -A function -A enabled
    )
    if [[ $signature != "${__ketraterm_completion_signature-}" ]]; then
        __ketraterm_completion_signature=$signature
        builtin printf -v __ketraterm_completion_epoch '%04x%04x%04x%04x%04x%04x%04x%04x' "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    if [[ $path_signature != "${__ketraterm_completion_path_signature-}" ]]; then
        __ketraterm_completion_path_signature=$path_signature
        builtin printf -v __ketraterm_completion_path_epoch '%04x%04x%04x%04x%04x%04x%04x%04x' "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    builtin printf '\033]633;KetraTermShellCommands;3;Ready;%s\a' "$__ketraterm_completion_epoch"
    builtin printf '\033]633;KetraTermCommands;3;Ready;%s\a' "$__ketraterm_completion_path_epoch"
    # Publish bounded shell tables without waiting for filesystem discovery.
    # No completer, user function, command lookup or PATH walk runs here.
    if [[ ${__ketraterm_completion_scan_epoch-} != "$__ketraterm_completion_epoch" ]]; then
        __ketraterm_completion_scan_id=$((${__ketraterm_completion_scan_id:-0} + 1))
        __ketraterm_publish_commands "$__ketraterm_completion_epoch" "$__ketraterm_completion_scan_id" Local
        __ketraterm_completion_scan_epoch=$__ketraterm_completion_epoch
    fi
    if [[ -n ${__ketraterm_completion_pid:-} ]]; then
        builtin kill -0 "$__ketraterm_completion_pid" 2>/dev/null && return
        __ketraterm_completion_pid=
        __ketraterm_completion_scan_time=$SECONDS
    fi
    if [[ ${__ketraterm_completion_path_scan_epoch-} == "$__ketraterm_completion_path_epoch" ]] && ((SECONDS - ${__ketraterm_completion_scan_time:-0} < 60)); then return; fi
    __ketraterm_completion_path_scan_epoch=$__ketraterm_completion_path_epoch
    __ketraterm_completion_scan_id=$((${__ketraterm_completion_scan_id:-0} + 1))
    # Only PATH discovery runs in the background; never accumulate workers.
    __ketraterm_completion_pid=$(
        # The worker must never write to the live terminal: even one printf
        # can be split and interleave with prompt/redraw bytes in the PTY.
        (
            __ketraterm_publish_commands "$__ketraterm_completion_path_epoch" "$__ketraterm_completion_scan_id" Path > "$KetraTerm_COMMAND_INVENTORY_DIRECTORY/path.pending" &&
                command "$__ketraterm_completion_mv" -f -- "$KetraTerm_COMMAND_INVENTORY_DIRECTORY/path.pending" "$KetraTerm_COMMAND_INVENTORY_DIRECTORY/path.ready"
        ) </dev/null >/dev/null 2>&1 &
        builtin printf '%s' "$!"
    )
}

# Earlier compgen versions only expose ambiguous line-delimited output.
if ((BASH_VERSINFO[0] < 5 || (BASH_VERSINFO[0] == 5 && BASH_VERSINFO[1] < 3))); then
    __ketraterm_completion_ready() { :; }
else
    # Parse 5.3-only syntax only on supported shells. This fixed definition never
    # evaluates command-name data. Current-shell capture avoids a fork per lookup.
    eval '__ketraterm_resolve_command() { REPLY=${ builtin type -p -- "$1" || :; }; }'
fi
