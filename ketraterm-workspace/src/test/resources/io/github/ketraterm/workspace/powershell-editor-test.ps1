# Observe the actual editor buffer, independently of terminal-cell extraction.
Set-PSReadLineOption -HistorySaveStyle SaveNothing
[Microsoft.PowerShell.PSConsoleReadLine]::ClearHistory()
$global:__KetraTestSnapshotSequence = 0
function global:__KetraTestSnapshot {
    $line = ''
    $cursor = 0
    [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($line))
    $global:__KetraTestSnapshotSequence++
    [Console]::Write(([string][char]27) + ']0;ketra-buffer;' + $global:__KetraTestSnapshotSequence + ';' + $cursor + ';' + $global:LASTEXITCODE + ';' + $encoded + ([string][char]7))
}
Set-PSReadLineKeyHandler -Chord 'F12,t' -ScriptBlock { global:__KetraTestSnapshot }
