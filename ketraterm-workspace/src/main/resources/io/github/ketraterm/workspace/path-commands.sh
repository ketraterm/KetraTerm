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
    builtin local -a cd_options files
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
    builtin local LC_ALL=C origin="$PWD" remaining="$PATH:" directory name count=0 size=0 enumerated=0
    builtin local finder
    # Process startup outweighs directory-type savings in Windows POSIX layers.
    if [[ ${OSTYPE-} == msys* || ${OSTYPE-} == cygwin* ]]; then
        finder=
    elif [[ -n ${ZSH_VERSION-} ]]; then
        finder=$(builtin whence -p find 2>/dev/null) || finder=
    else
        finder=$(builtin type -P find 2>/dev/null) || finder=
    fi
    # Probe options without walking a directory. An individual directory may
    # still fail (for example a symlink loop); only that directory falls back.
    if [[ -n $finder ]]; then
        command "$finder" -L . -mindepth 0 -maxdepth 0 ! -type d -print0 >/dev/null 2>&1 || finder=
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
        # find can use directory-entry types without a second stat for every
        # executable. Keep the shell's own -x check (including ACLs/mount rules).
        # A completion marker rejects partial output; unsupported find variants
        # fall back to shell globbing without admitting incomplete output.
        enumerated=0
        if [[ -n $finder ]]; then
            if [[ -n ${ZSH_VERSION-} ]]; then
                files=("${(@0)$(command "$finder" -L . -mindepth 1 -maxdepth 1 ! -type d -print0 2>/dev/null && builtin printf '__ketraterm_inventory_end')}")
            else
                builtin mapfile -d '' -t files < <(command "$finder" -L . -mindepth 1 -maxdepth 1 ! -type d -print0 2>/dev/null && builtin printf '__ketraterm_inventory_end')
            fi
            if [[ ${files[-1]-} == __ketraterm_inventory_end ]]; then
                builtin unset 'files[-1]'
                enumerated=1
            fi
        fi
        if (( ! enumerated )); then files=(*); fi
        for name in "${files[@]}"; do
            name=${name#./}
            [[ -z ${names[$name]-} ]] || continue
            [[ -x $name ]] || continue
            [[ $enumerated == 1 || ! -d $name ]] || continue
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
