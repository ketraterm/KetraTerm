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
__ketraterm_publish_commands() {
    if [[ -z $__ketraterm_completion_base64 ]]; then return; fi
    local LC_ALL=C name replacement size=0 count=0 encoded bare resolved REPLY kind expansion
    local -a entries=() available=() keywords=()
    local -A seen=()
    # Reserved grammar tokens are not executable command names; quoting them
    # (for example '!') would turn valid shell syntax into a missing command.
    builtin compgen -V keywords -A keyword
    for name in "${keywords[@]}"; do seen["$name"]=1; done
    # Bash 5.3's array output preserves names containing whitespace without
    # spawning another shell or resolving every executable through PATH again.
    builtin compgen -V available -c
    for name in "${available[@]}"; do
        [[ -n $name && $name != __ketraterm_* && $name != *[$'\001'-$'\037'$'\177']* ]] || continue
        [[ -z ${seen["$name"]+present} ]] || continue
        seen["$name"]=1
        ((${#name} <= 512)) || continue
        replacement=$name
        if [[ ($OSTYPE == cygwin* || $OSTYPE == msys*) && $name == ?*.exe ]]; then
            bare=${name%.exe}
            __ketraterm_resolve_command "$bare"
            resolved=$REPLY
            __ketraterm_resolve_command "$name"
            if [[ -n $resolved && -n $REPLY && $resolved -ef $REPLY ]]; then replacement=$bare; fi
        fi
        if [[ $replacement == *[^a-zA-Z0-9_./-]* ]]; then replacement="'${replacement//\'/\'\\\'\'}'"; fi
        kind=Command
        expansion=
        if [[ ${BASH_ALIASES[$name]+present} ]]; then
            kind=Alias
            expansion=${BASH_ALIASES[$name]}
            # Retain alias identity even when its definition exceeds the metadata budget.
            if ((${#expansion} > 4096)); then expansion=; fi
        fi
        size=$((size + ${#name} + ${#replacement} + ${#kind} + ${#expansion} + 4))
        ((size < 180000 && count < 8192)) || break
        entries+=("$name" "$replacement" "$kind" "$expansion")
        ((count+=1))
    done
    encoded=$(
        if ((count)); then printf '%s\0' "${entries[@]}"; fi | command "$__ketraterm_completion_base64"
    ) || { printf '\033]633;KetraTermCommands;2;Result;%s;Unavailable\a' "$1"; return; }
    encoded=${encoded//$'\n'/}
    encoded=${encoded//$'\r'/}
    printf '\033]633;KetraTermCommands;2;Result;%s;%s\a' "$1" "$encoded"
}

__ketraterm_completion_ready() {
    [[ $- == *i* && -t 1 ]] || return
    [[ -n $__ketraterm_completion_base64 ]] || return
    local signature epoch
    signature=$(declare -p PATH BASH_ALIASES; printf '%q\n' "$PWD"; builtin compgen -A function -A builtin)
    if [[ $signature != "${__ketraterm_completion_signature-}" ]]; then
        __ketraterm_completion_signature=$signature
        printf -v __ketraterm_completion_epoch '%04x%04x%04x%04x%04x%04x%04x%04x' \
            "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM"
    fi
    epoch=$__ketraterm_completion_epoch
    printf '\033]633;KetraTermCommands;2;Begin;%s\a' "$epoch"
    # Reuse a completed inventory while the same environment refreshes. Never
    # accumulate scans; changed environments reject the old worker's result.
    if [[ -n ${__ketraterm_completion_pid:-} ]] && builtin kill -0 "$__ketraterm_completion_pid" 2>/dev/null; then return; fi
    # Launch in a subshell so discovery neither changes the user's $! nor adds
    # jobs/notifications to the interactive shell. Only metadata reaches its TTY.
    __ketraterm_completion_pid=$(
        __ketraterm_publish_commands "$epoch" </dev/null >/dev/tty 2>/dev/null &
        printf '%s' "$!"
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
