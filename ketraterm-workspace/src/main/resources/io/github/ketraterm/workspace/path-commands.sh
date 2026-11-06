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

# A subshell isolates cwd and shell options from the interactive shell. Checking
# basenames after chdir avoids repeated traversal of mounted/network ancestors.
# Emit NUL-delimited names only; neither filenames nor PATH entries are evaluated.
__ketraterm_collect_path_commands() (
    builtin local -a cd_options find_options files
    cd_options=()
    find_options=()
    if [[ -n ${ZSH_VERSION-} ]]; then
        builtin emulate -L zsh
        builtin zmodload -F zsh/system b:sysread || {
            builtin printf '__ketraterm_inventory_unavailable'
            return
        }
        # Even builtin cd invokes user chpwd hooks unless explicitly suppressed.
        cd_options=(-q)
    fi
    builtin local LC_ALL=C origin="$PWD" remaining="$PATH:" directory name count=0 size=0 enumerated=0
    builtin local path_count=0 scanned_count=0 scanned_size=0 buffer= chunk=
    # Validate before locating find, since that lookup also traverses PATH.
    while [[ -n $remaining ]]; do
        ((path_count+=1))
        if ((path_count > 4096)); then
            builtin printf '__ketraterm_inventory_overflow'
            return
        fi
        remaining=${remaining#*:}
    done
    remaining="$PATH:"
    builtin local finder
    if [[ -n ${ZSH_VERSION-} ]]; then
        finder=$(builtin whence -p find 2>/dev/null) || finder=
    else
        finder=$(builtin type -P find 2>/dev/null) || finder=
    fi
    [[ -z $finder || $finder == /* ]] || finder=$origin/$finder
    # Probe without walking a directory. The standard utility path also works
    # when PATH was customized or resolves Windows' unrelated find.exe.
    if [[ -z $finder ]] || ! builtin command "$finder" . -mindepth 1 -maxdepth 0 -print0 >/dev/null 2>&1; then
        finder=find
        find_options=(-p)
        if ! builtin command "${find_options[@]}" "$finder" . -mindepth 1 -maxdepth 0 -print0 >/dev/null 2>&1; then
            builtin printf '__ketraterm_inventory_unavailable'
            return
        fi
    fi
    builtin local -A directories names
    directories=()
    names=()
    while [[ -n $remaining ]]; do
        directory=${remaining%%:*}
        remaining=${remaining#*:}
        [[ -n $directory ]] || directory=.
        [[ $directory == /* ]] || directory=$origin/$directory
        # Physical resolution is needed for .. through symlinks. Otherwise the
        # logical form avoids expensive getcwd/realpath walks on Windows mounts.
        if [[ /$directory/ == */../* ]]; then
            builtin cd "${cd_options[@]}" -P -- "$directory" 2>/dev/null || continue
        else
            builtin cd "${cd_options[@]}" -L -- "$directory" 2>/dev/null || continue
        fi
        [[ -z ${directories[$PWD]-} ]] || continue
        directories[$PWD]=1
        # Stream every entry, including directories and non-executables. Budget
        # before filtering so sparse executable directories cannot evade limits.
        # An empty record is the success marker; filenames can never be empty.
        enumerated=0
        buffer=
        while :; do
            if [[ -n ${ZSH_VERSION-} ]]; then
                # Zsh read -d changes the controlling tty even when stdin is a
                # pipe. Its restoration races the interactive editor's raw mode.
                builtin sysread -i 0 -s 8192 chunk || break
                buffer+=$chunk
                if ((${#buffer} > 16777216)); then
                    builtin printf '__ketraterm_inventory_overflow'
                    return
                fi
                files=("${(@0)buffer}")
                # Retain the unfinished record across reads, including an empty tail.
                buffer=${files[-1]}
                files[-1]=()
            else
                # Use Bash's array reader without retaining a whole directory.
                builtin mapfile -d '' -n 256 -t files || break
                ((${#files[@]})) || break
            fi
            for name in "${files[@]}"; do
                if [[ -z $name ]]; then
                    enumerated=1
                    break 2
                fi
                ((scanned_count+=1))
                ((scanned_size+=${#name}+1))
                if ((scanned_count > 131072 || scanned_size > 16777216)); then
                    builtin printf '__ketraterm_inventory_overflow'
                    return
                fi
                name=${name#./}
                [[ -z ${names[$name]-} ]] || continue
                [[ -x $name && ! -d $name ]] || continue
                names[$name]=1
                ((count+=1))
                ((size+=${#name}+1))
                if ((count > 32768 || size > 2097152)); then
                    builtin printf '__ketraterm_inventory_overflow'
                    return
                fi
                builtin printf '%s\0' "$name"
            done
        done < <(builtin command "${find_options[@]}" "$finder" . -mindepth 1 -maxdepth 1 -print0 2>/dev/null && builtin printf '\0')
        # Returning early closes the pipe; a still-enumerating finder receives
        # SIGPIPE instead of finishing an unbounded scan in the background.
        if (( ! enumerated )); then
            builtin printf '__ketraterm_inventory_unavailable'
            return
        fi
    done
    builtin printf '__ketraterm_inventory_end'
)
