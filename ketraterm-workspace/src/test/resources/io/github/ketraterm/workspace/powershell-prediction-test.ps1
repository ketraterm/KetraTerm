$options = Get-Command Set-PSReadLineOption
if (-not $options.Parameters.ContainsKey('PredictionSource')) {
    [Console]::Write(([string][char]27) + ']0;ketra-prediction-unavailable' + ([string][char]7))
    return
}
# Keep cold module discovery outside the bounded interactive query tested below.
$null = TabExpansion2 -inputScript 'Get-Chi' -cursorColumn 7
Set-PSReadLineOption -PredictionSource History -ErrorAction Stop
if ($options.Parameters.ContainsKey('PredictionViewStyle')) {
    Set-PSReadLineOption -PredictionViewStyle InlineView -ErrorAction Stop
}
[Microsoft.PowerShell.PSConsoleReadLine]::AddToHistory('Get-ChildItem -Name ketra-native-prediction')
Set-PSReadLineKeyHandler -Chord 'F12,s' -ScriptBlock {
    $line = ''
    $cursor = 0
    [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
    [Microsoft.PowerShell.PSConsoleReadLine]::Replace(0, $line.Length, 'Get-Chi tail')
    [Microsoft.PowerShell.PSConsoleReadLine]::SetCursorPosition(7)
    global:__KetraTestSnapshot
}
Set-PSReadLineKeyHandler -Chord 'F12,w' -ScriptBlock {
    $line = ''
    $cursor = 0
    [Microsoft.PowerShell.PSConsoleReadLine]::GetBufferState([ref]$line, [ref]$cursor)
    [Microsoft.PowerShell.PSConsoleReadLine]::Replace(0, $line.Length, 'Get-Chi  ')
    [Microsoft.PowerShell.PSConsoleReadLine]::SetCursorPosition(9)
    global:__KetraTestSnapshot
}
[Console]::Write(([string][char]27) + ']0;ketra-prediction-ready' + ([string][char]7))
