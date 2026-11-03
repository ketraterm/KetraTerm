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

# A subshell isolates cwd and glob options from the interactive shell. Checking
# basenames after chdir avoids repeated traversal of mounted/network ancestors.
# Emit NUL-delimited names only; neither filenames nor PATH entries are evaluated.
__ketraterm_collect_path_commands() (
    builtin local -a cd_options
    cd_options=()
    if [[ -n ${ZSH_VERSION-} ]]; then
        builtin emulate -L zsh
        builtin setopt NULL_GLOB GLOB_DOTS
        # Even builtin cd invokes user chpwd hooks unless explicitly suppressed.
        cd_options=(-q)
    else
        builtin unset GLOBIGNORE
        builtin shopt -u failglob
        builtin shopt -s nullglob dotglob
        builtin set +f
    fi
    builtin local LC_ALL=C origin="$PWD" remaining="$PATH:" directory name count=0 size=0
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
        for name in *; do
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
    done
    builtin printf '__ketraterm_inventory_end'
)
