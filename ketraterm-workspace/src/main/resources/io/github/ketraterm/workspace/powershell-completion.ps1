# Native completion runs inside the existing PSReadLine session.
function global:__KetraTermCompletionReady {
    if (-not $global:__KetraTermCompletionInstalled) { return }
    $global:__KetraTermCompletionEpoch = [Guid]::NewGuid().ToString('N')
    $global:__KetraTermCompletionSequence = 0L
    $capabilities = if ($global:__KetraTermCompletionPrefixResetInstalled) { ';PrefixReset' } else { '' }
    [Console]::Write(([string][char]27) + ']633;KetraTermCompletion;2;Ready;' + $global:__KetraTermCompletionEpoch + $capabilities + ([string][char]7))
}

function global:__KetraTermCompletionEncode([string] $Value) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
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

function global:__KetraTermComplete {
    # Module discovery and registered completers may draw progress over PSReadLine.
    # This query must not repaint the terminal: the session rejects changed frames.
    # A local preference leaves the user's interactive preference unchanged.
    $ProgressPreference = 'SilentlyContinue'
    $savedExitCode = $global:LASTEXITCODE
    $global:__KetraTermCompletionSequence++
    $prefix = 'KetraTermCompletion;2;Result;' + $global:__KetraTermCompletionEpoch + ';' + $global:__KetraTermCompletionSequence + ';'
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

$global:__KetraTermCompletionPrefixResetInstalled = $false
try {
    if ($global:__KetraTermCompletionInstalled) {
        Set-PSReadLineKeyHandler -Chord 'F12,r' -ScriptBlock { global:__KetraTermResetCompletionPrefix } -ErrorAction Stop
        $global:__KetraTermCompletionPrefixResetInstalled = $true
    }
} catch {
    # Completion remains usable when the editor cannot register prefix replacement.
}

