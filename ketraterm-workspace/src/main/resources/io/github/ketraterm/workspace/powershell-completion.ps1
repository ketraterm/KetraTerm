# Native completion runs inside the existing PSReadLine session.
function global:__KetraTermCompletionReady {
    # Setting the same EditMode can also reset PSReadLine's key bindings.
    global:__KetraTermInstallCompletionHandlers
    if (-not $global:__KetraTermCompletionInstalled) { return }
    $global:__KetraTermCompletionEpoch = [Guid]::NewGuid().ToString('N')
    $capabilities = if ($global:__KetraTermCompletionPrefixResetInstalled) { ';PrefixReset' } else { '' }
    if ($global:__KetraTermCompletionBufferStateInstalled) { $capabilities += ';BufferState' }
    [Console]::Write(([string][char]27) + ']633;KetraTermCompletion;3;Ready;' + $global:__KetraTermCompletionEpoch + $capabilities + ([string][char]7))
}

function global:__KetraTermCompletionAppend([char] $Digit) {
    if ($null -eq $global:__KetraTermCompletionRequest) { return }
    if ($global:__KetraTermCompletionRequest.Length -ge 19 -or [int]$Digit -lt 48 -or [int]$Digit -gt 57) {
        $global:__KetraTermCompletionRequest = $null
        return
    }
    $global:__KetraTermCompletionRequest += $Digit
}

function global:__KetraTermCompletionCommit([bool] $BufferOnly = $false) {
    $digits = $global:__KetraTermCompletionRequest
    $global:__KetraTermCompletionRequest = $null
    if ($null -eq $digits -or $digits.Length -ne 19) { return }
    $requestId = 0L
    if ([long]::TryParse($digits, [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$requestId) -and
        $requestId -gt 0) {
        if ($BufferOnly) { global:__KetraTermConfirmBuffer $requestId }
        else { global:__KetraTermComplete $requestId }
    }
}

function global:__KetraTermCompletionEncode([string] $Value) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

# Buffer confirmation must not wait for module discovery or user completers.
function global:__KetraTermConfirmBuffer([long] $RequestId) {
    if ($RequestId -le 0) { return }
    $savedExitCode = $global:LASTEXITCODE
    $prefix = 'KetraTermCompletion;3;Result;' + $global:__KetraTermCompletionEpoch + ';' + $RequestId + ';'
    $body = $prefix + 'Failed'
    try {
        $line = ''
        $cursor = 0
        [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
        if ($line.Length -le 4096) {
            $body = $prefix + 'Ok;' + $cursor + ';' + $cursor + ';' + $cursor + ';' +
                (global:__KetraTermCompletionEncode $line) + ';;0'
        }
    } catch {
        # An unavailable editor buffer is a failed confirmation, never terminal output.
    } finally {
        $global:LASTEXITCODE = $savedExitCode
    }
    [Console]::Write(([string][char]27) + ']633;' + $body + ([string][char]7))
}

# Suggestion acceptance reconstructs this prefix instead of guessing the editor's
# Backspace units. Replace uses UTF-16 offsets on every supported PSReadLine version.
function global:__KetraTermResetCompletionPrefix {
    $savedExitCode = $global:LASTEXITCODE
    try {
        $line = ''
        $cursor = 0
        [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
        if ($cursor -gt 0) {
            [Microsoft.PowerShell.PSConsoleReadLine]::Replace(0, $cursor, '')
        }
    } finally {
        $global:LASTEXITCODE = $savedExitCode
    }
}

function global:__KetraTermComplete([long] $RequestId) {
    if ($RequestId -le 0) { return }
    # Suppress discovery/completer progress without changing the user's interactive preference.
    $ProgressPreference = 'SilentlyContinue'
    $savedExitCode = $global:LASTEXITCODE
    $prefix = 'KetraTermCompletion;3;Result;' + $global:__KetraTermCompletionEpoch + ';' + $RequestId + ';'
    $body = $prefix + 'Failed'
    try {
        $line = ''
        $cursor = 0
        [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
        if ($line.Length -le 4096) {
            $externalCommandName = ''
            if ($line.Length -gt 0) {
                $parseTokens = $null
                $parseErrors = $null
                $ast = [Management.Automation.Language.Parser]::ParseInput($line, [ref]$parseTokens, [ref]$parseErrors)
                # Resolve the innermost command containing the cursor, including trailing whitespace.
                # Command names are inspected as data; dynamic invocations are never evaluated.
                $activeCommand = $null
                foreach ($node in $ast.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] }, $true)) {
                    $begin = $node.Extent.StartOffset
                    $finish = $node.Extent.EndOffset
                    if ($begin -gt $cursor -or ($finish -lt $cursor -and $line.Substring($finish, $cursor - $finish).Trim().Length -gt 0)) { continue }
                    if ($null -eq $activeCommand -or $begin -gt $activeCommand.Extent.StartOffset) { $activeCommand = $node }
                }
                # Application identity only selects argument-completion policy. Looking up an
                # unfinished command name repeats discovery that TabExpansion2 already owns.
                if ($null -ne $activeCommand -and $activeCommand.CommandElements.Count -gt 0 -and
                    $cursor -gt $activeCommand.CommandElements[0].Extent.EndOffset) {
                    $name = $activeCommand.GetCommandName()
                    if (-not [string]::IsNullOrEmpty($name)) {
                        $command = $ExecutionContext.InvokeCommand.GetCommand($name, [Management.Automation.CommandTypes]::All)
                        if ($command -is [Management.Automation.AliasInfo]) { $command = $command.ResolvedCommand }
                        if ($command -is [Management.Automation.ApplicationInfo]) { $externalCommandName = $command.Name }
                    }
                }
            }
            $encodedCommandName = global:__KetraTermCompletionEncode $externalCommandName
            # Even a failing completer can acknowledge an unchanged, validated buffer.
            $body = $prefix + 'Ok;' + $cursor + ';' + $cursor + ';' + $cursor + ';' + (global:__KetraTermCompletionEncode $line) + ';' + $encodedCommandName + ';0'
            if ($line.Length -eq 0) {
                # TabExpansion2 rejects empty input. Discover a bounded set of actual commands.
                $discovered = Microsoft.PowerShell.Core\Get-Command -Name '*' -TotalCount 256 -ErrorAction SilentlyContinue |
                    Microsoft.PowerShell.Utility\Sort-Object -Property Name
                $matches = @(foreach ($command in $discovered) {
                    if ($command.Name.StartsWith('__KetraTerm', [StringComparison]::OrdinalIgnoreCase)) { continue }
                    $name = $command.Name
                    $value = $name
                    $parseTokens = $null
                    $parseErrors = $null
                    $ast = [Management.Automation.Language.Parser]::ParseInput($name, [ref]$parseTokens, [ref]$parseErrors)
                    $statement = $null
                    if ($ast.EndBlock -and $ast.EndBlock.Statements.Count -gt 0) { $statement = $ast.EndBlock.Statements[0] }
                    if ($parseErrors.Count -gt 0 -or
                        $statement -isnot [Management.Automation.Language.PipelineAst] -or
                        $statement.PipelineElements.Count -ne 1 -or
                        $statement.PipelineElements[0] -isnot [Management.Automation.Language.CommandAst] -or
                        $statement.PipelineElements[0].GetCommandName() -cne $name) {
                        $value = '& ''' + [Management.Automation.Language.CodeGeneration]::EscapeSingleQuotedStringContent($name) + ''''
                    }
                    $description = [string]$command.Definition
                    if ([string]::IsNullOrEmpty($description)) { $description = $name }
                    [Management.Automation.CompletionResult]::new($value, $name,
                        [Management.Automation.CompletionResultType]::Command, $description)
                })
                $start = 0
                $end = 0
            } else {
                $result = TabExpansion2 -inputScript $line -cursorColumn $cursor
                $matches = $result.CompletionMatches
                $start = $result.ReplacementIndex
                $end = $start + $result.ReplacementLength
            }
            $header = $prefix + 'Ok;' + $cursor + ';' + $start + ';' +
                $end + ';' + (global:__KetraTermCompletionEncode $line) + ';' + $encodedCommandName + ';'
            $items = [Collections.Generic.List[string]]::new()
            $size = $header.Length + 3
            foreach ($match in $matches) {
                if ($items.Count -ge 128) { break }
                $value = [string]$match.CompletionText
                $label = [string]$match.ListItemText
                $detail = [string]$match.ToolTip
                if ($match.ResultType -eq 'Command' -and $value -ceq $label) {
                    $shortName = [IO.Path]::GetFileNameWithoutExtension($label).ToLowerInvariant()
                    $prefixMatches = $start -ge 0 -and $cursor -ge $start -and
                        $shortName.StartsWith($line.Substring($start, $cursor - $start), [StringComparison]::OrdinalIgnoreCase)
                    if ($shortName -ne $label -and $prefixMatches) {
                        $longCommand = $ExecutionContext.InvokeCommand.GetCommand($label, [Management.Automation.CommandTypes]::All)
                        if ($longCommand -is [Management.Automation.ApplicationInfo] -or
                            $longCommand -is [Management.Automation.ExternalScriptInfo]) {
                            $shortCommand = $ExecutionContext.InvokeCommand.GetCommand($shortName, [Management.Automation.CommandTypes]::All)
                            if ($null -ne $shortCommand -and $shortCommand.CommandType -eq $longCommand.CommandType -and
                                $shortCommand.Path -ceq $longCommand.Path) {
                                $value = $shortName
                            }
                        }
                    }
                }
                if ($value.Length -gt 4096 -or $label.Length -gt 512) { continue }
                if ($detail.Length -gt 1024) {
                    $detailEnd = 1024
                    if ([char]::IsHighSurrogate($detail[$detailEnd - 1])) { $detailEnd-- }
                    $detail = $detail.Substring(0, $detailEnd)
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
        try {
            if ($body.StartsWith($prefix + 'Ok;', [StringComparison]::Ordinal)) {
                # Registered completers can mutate the editor. Confirm the sampled buffer,
                # not an earlier state that happened to produce these candidates.
                $currentLine = ''
                $currentCursor = 0
                [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$currentLine, [ref]$currentCursor)
                if ($currentLine -cne $line -or $currentCursor -ne $cursor) {
                    $body = $prefix + 'Failed'
                }
            }
        } catch {
            $body = $prefix + 'Failed'
        }
        $global:LASTEXITCODE = $savedExitCode
    }
    [Console]::Write(([string][char]27) + ']633;' + $body + ([string][char]7))
}

function global:__KetraTermInstallCompletionHandlers {
    $global:__KetraTermCompletionInstalled = $false
    $global:__KetraTermCompletionPrefixResetInstalled = $false
    $global:__KetraTermCompletionBufferStateInstalled = $true
    $global:__KetraTermCompletionRequest = $null
    try {
        if (-not (Get-Module PSReadLine)) { return }
        $mode = (Get-PSReadLineOption).EditMode
        $handlers = @{
            'q' = { $global:__KetraTermCompletionRequest = '' }
            'e' = { global:__KetraTermCompletionCommit }
            'b' = { global:__KetraTermCompletionCommit $true }
            'r' = { global:__KetraTermResetCompletionPrefix }
        }
        foreach ($digit in 0..9) {
            $handlers[[string]$digit] = { param($key, $arg) global:__KetraTermCompletionAppend $key.KeyChar }
        }
        $global:__KetraTermCompletionPrefixResetInstalled = $true
        foreach ($suffix in $handlers.Keys) {
            try {
                if ($mode -eq 'Vi') {
                    foreach ($viMode in @('Insert', 'Command')) {
                        Set-PSReadLineKeyHandler -Chord ('F12,' + $suffix) -ScriptBlock $handlers[$suffix] -ViMode $viMode -ErrorAction Stop
                    }
                } else {
                    Set-PSReadLineKeyHandler -Chord ('F12,' + $suffix) -ScriptBlock $handlers[$suffix] -ErrorAction Stop
                }
            } catch {
                if ($suffix -eq 'b') {
                    $global:__KetraTermCompletionBufferStateInstalled = $false
                    continue
                }
                if ($suffix -ne 'r') { throw }
                $global:__KetraTermCompletionPrefixResetInstalled = $false
            }
        }
        $global:__KetraTermCompletionInstalled = $true
    } catch {
        $global:__KetraTermCompletionPrefixResetInstalled = $false
    }
}

