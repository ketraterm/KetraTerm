# Observe the actual editor buffer, independently of terminal-cell extraction.
Set-PSReadLineOption -HistorySaveStyle SaveNothing
[Microsoft.PowerShell.PSConsoleReadLine]::ClearHistory()
$global:__KetraTestPrefixes = @(
    ('echo e' + [char]0x301),
    ('echo ' + [char]::ConvertFromUtf32(0x1f680)),
    ('echo ' + [char]::ConvertFromUtf32(0x1f469) + [char]0x200d + [char]::ConvertFromUtf32(0x1f4bb)),
    ''
)
$global:__KetraTestCase = 0
$global:__KetraTestSnapshotSequence = 0
function global:__KetraTestSnapshot {
    $line = ''
    $cursor = 0
    [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($line))
    $global:__KetraTestSnapshotSequence++
    [Console]::Write(([string][char]27) + ']0;ketra-buffer;' + $global:__KetraTestSnapshotSequence + ';' + $cursor + ';' + $global:LASTEXITCODE + ';' + $encoded + ([string][char]7))
}
Set-PSReadLineKeyHandler -Chord 'F12,s' -ScriptBlock {
    $line = ''
    $cursor = 0
    [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
    $prefix = $global:__KetraTestPrefixes[$global:__KetraTestCase++]
    $suffix = ' tail ' + [char]::ConvertFromUtf32(0x1f680)
    [Microsoft.PowerShell.PSConsoleReadLine]::Replace(0, $line.Length, $prefix + $suffix)
    [Microsoft.PowerShell.PSConsoleReadLine]::SetCursorPosition($prefix.Length)
    $global:LASTEXITCODE = 77
    global:__KetraTestSnapshot
}
Set-PSReadLineKeyHandler -Chord 'F12,t' -ScriptBlock { global:__KetraTestSnapshot }
[Console]::Write(([string][char]27) + ']0;ketra-ready' + ([string][char]7))
