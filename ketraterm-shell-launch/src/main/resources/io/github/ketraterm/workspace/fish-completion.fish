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

set -g __ketraterm_fish_completion_base64 (command -s base64)
set -g __ketraterm_fish_completion_bindings
set -g __ketraterm_fish_completion_prompt 0

function __ketraterm_fish_confirm_buffer --argument-names request_id
    set -l header "KetraTermCompletion;3;Result;$__ketraterm_fish_completion_epoch;$request_id;"
    set -l body "$header"Failed
    # Count before collecting: command substitution trims trailing newlines, which
    # must never turn a multiline editor buffer into a confirmed single line.
    set -l lengths (commandline --current-buffer | string length)
    if test (count $lengths) -eq 1; and test "$lengths[1]" -le 4096; and not commandline --paging-mode
        set -l line (commandline --current-buffer | string collect)
        set -l cursor (commandline --cursor)
        if test "$cursor" -ge 0; and test "$cursor" -le "$lengths[1]"; and not string match --quiet --regex '[\x00-\x1f\x7f-\x9f]' -- "$line"
            # Fish indexes code points; the session protocol indexes UTF-16.
            set -l utf16_length (string replace --all --regex '[\x{10000}-\x{10ffff}]' xx -- "$line" | string length)
            if test "$utf16_length" -le 4096
                set -l prefix (string sub --length "$cursor" -- "$line")
                set -l utf16_cursor (string replace --all --regex '[\x{10000}-\x{10ffff}]' xx -- "$prefix" | string length)
                set -l encoded (printf '%s' "$line" | command "$__ketraterm_fish_completion_base64" 2>/dev/null)
                if test $status -eq 0
                    set encoded (string join '' -- $encoded)
                    set body "$header""Ok;$utf16_cursor;$utf16_cursor;$utf16_cursor;$encoded;;0"
                end
            end
        end
    end
    # Reading the buffer must not repaint/re-run the prompt and retire the query.
    printf '\e]633;%s\a' "$body"
end

function __ketraterm_fish_completion_key --argument-names key
    set -l previous_status $status
    switch "$key"
        case q
            set -g __ketraterm_fish_completion_request ''
        case b e
            if set -q __ketraterm_fish_completion_request
                set -l request_id "$__ketraterm_fish_completion_request"
                set -e __ketraterm_fish_completion_request
                if string match --quiet --regex '^[0-9]{19}$' -- "$request_id"; and string match --quiet --regex '[1-9]' -- "$request_id"; and test "$request_id" -le 9223372036854775807 2>/dev/null
                    __ketraterm_fish_confirm_buffer "$request_id"
                end
            end
        case '*'
            if set -q __ketraterm_fish_completion_request
                if test (string length -- "$__ketraterm_fish_completion_request") -lt 19; and string match --quiet --regex '^[0-9]$' -- "$key"
                    set -g __ketraterm_fish_completion_request "$__ketraterm_fish_completion_request$key"
                else
                    set -e __ketraterm_fish_completion_request
                end
            end
    end
    return $previous_status
end

function __ketraterm_fish_completion_ready
    test -n "$__ketraterm_fish_completion_base64"; and test -x "$__ketraterm_fish_completion_base64"; or return
    set -l modes (bind --list-modes)
    test (count $modes) -gt 0; or return
    set -l prefix (printf '\e[24~')
    set -l keys q b e 0 1 2 3 4 5 6 7 8 9
    # Reserve the whole frame only when it preserves every existing binding.
    # Remember our own installed definitions so later prompts detect user edits.
    for mode in $modes
        if bind --user --mode "$mode" "$prefix" >/dev/null 2>&1; or bind --preset --mode "$mode" "$prefix" >/dev/null 2>&1
            return
        end
        for key in $keys
            set -l binding (bind --user --mode "$mode" "$prefix$key" 2>/dev/null)
            if test $status -eq 0
                contains -- "$binding" $__ketraterm_fish_completion_bindings; or return
            else if bind --preset --mode "$mode" "$prefix$key" >/dev/null 2>&1
                return
            end
        end
    end
    for mode in $modes
        for key in $keys
            if not bind --user --mode "$mode" "$prefix$key" >/dev/null 2>&1
                bind --user --mode "$mode" "$prefix$key" "__ketraterm_fish_completion_key $key" 2>/dev/null; or return
                set -ga __ketraterm_fish_completion_bindings (bind --user --mode "$mode" "$prefix$key")
            end
        end
    end
    set -e __ketraterm_fish_completion_request
    set -g __ketraterm_fish_completion_prompt (math "$__ketraterm_fish_completion_prompt + 1")
    set -g __ketraterm_fish_completion_epoch (printf '%016x%016x' "$fish_pid" "$__ketraterm_fish_completion_prompt")
    printf '\e]633;KetraTermCompletion;3;Ready;%s;BufferState\a' "$__ketraterm_fish_completion_epoch"
end
