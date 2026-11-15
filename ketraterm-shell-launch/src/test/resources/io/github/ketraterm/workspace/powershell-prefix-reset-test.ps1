$global:__KetraTestPrefixes = @(
    ('echo e' + [char]0x301),
    ('echo ' + [char]::ConvertFromUtf32(0x1f680)),
    ('echo ' + [char]::ConvertFromUtf32(0x1f469) + [char]0x200d + [char]::ConvertFromUtf32(0x1f4bb)),
    ''
)
$global:__KetraTestCase = 0
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
[Console]::Write(([string][char]27) + ']0;ketra-ready' + ([string][char]7))
