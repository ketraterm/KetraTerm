# Native completion runs inside the existing PSReadLine session.
function global:__KetraTermCompletionReady {
    if (-not $global:__KetraTermCompletionInstalled) { return }
    $global:__KetraTermCompletionEpoch = [Guid]::NewGuid().ToString('N')
    $global:__KetraTermCompletionSequence = 0L
    [Console]::Write(([string][char]27) + ']633;KetraTermCompletion;1;Ready;' + $global:__KetraTermCompletionEpoch + ([string][char]7))
}

function global:__KetraTermCompletionEncode([string] $Value) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

function global:__KetraTermComplete {
    $savedExitCode = $global:LASTEXITCODE
    $global:__KetraTermCompletionSequence++
    $prefix = 'KetraTermCompletion;1;Result;' + $global:__KetraTermCompletionEpoch + ';' + $global:__KetraTermCompletionSequence + ';'
    $body = $prefix + 'Failed'
    try {
        $line = ''
        $cursor = 0
        [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
        if ($line.Length -eq 0) {
            # TabExpansion2 rejects empty input on Windows PowerShell; preserve other sources.
            $body = $prefix + 'Ok;0;0;0;;0'
        } elseif ($line.Length -le 4096) {
            # Even a failing completer can acknowledge an unchanged, validated buffer.
            $body = $prefix + 'Ok;' + $cursor + ';' + $cursor + ';' + $cursor + ';' + (global:__KetraTermCompletionEncode $line) + ';0'
            $result = TabExpansion2 -inputScript $line -cursorColumn $cursor
            $header = $prefix + 'Ok;' + $cursor + ';' + $result.ReplacementIndex + ';' +
                ($result.ReplacementIndex + $result.ReplacementLength) + ';' + (global:__KetraTermCompletionEncode $line) + ';'
            $items = [Collections.Generic.List[string]]::new()
            $size = $header.Length + 3
            foreach ($match in $result.CompletionMatches) {
                if ($items.Count -ge 128) { break }
                $value = [string]$match.CompletionText
                $label = [string]$match.ListItemText
                $detail = [string]$match.ToolTip
                if ($value.Length -gt 4096 -or $label.Length -gt 512) { continue }
                if ($detail.Length -gt 1024) {
                    $end = 1024
                    if ([char]::IsHighSurrogate($detail[$end - 1])) { $end-- }
                    $detail = $detail.Substring(0, $end)
                }
                $item = [string]$match.ResultType + ';' + (global:__KetraTermCompletionEncode $value) + ';' +
                    (global:__KetraTermCompletionEncode $label) + ';' + (global:__KetraTermCompletionEncode $detail)
                if ($size + $item.Length + 1 -gt 262000) { break }
                $items.Add($item)
                $size += $item.Length + 1
            }
            $body = $header + $items.Count
            if ($items.Count -gt 0) { $body += ';' + [string]::Join(';', $items) }
        }
    } catch {
        # Fail without printing errors, command text, or candidate data to the terminal.
    } finally {
        $global:LASTEXITCODE = $savedExitCode
    }
    [Console]::Write(([string][char]27) + ']633;' + $body + ([string][char]7))
}

try {
    if (Get-Module PSReadLine) {
        Set-PSReadLineKeyHandler -Chord 'F12,e' -ScriptBlock { global:__KetraTermComplete } -ErrorAction Stop
        $global:__KetraTermCompletionInstalled = $true
    }
} catch {
    $global:__KetraTermCompletionInstalled = $false
}

