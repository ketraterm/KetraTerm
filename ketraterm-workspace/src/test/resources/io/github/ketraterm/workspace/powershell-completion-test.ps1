# Replace only the line-editor boundary; use the real PowerShell completion engine.
Add-Type @'
namespace Microsoft.PowerShell {
    public static class PSConsoleReadLine {
        public static string Line;
        public static int Cursor = -1;
        public static void GetBufferState(ref string line, ref int cursor) {
            line = Line;
            cursor = Cursor < 0 ? Line.Length : Cursor;
        }
    }
}
'@
function global:Get-Module { param([string] $Name) [pscustomobject]@{ Name = 'PSReadLine' } }
$global:TestEditMode = 'Windows'
$global:CompletionHandlers = @{}
function global:Get-PSReadLineOption { [pscustomobject]@{ EditMode = $global:TestEditMode } }
function global:Set-PSReadLineKeyHandler {
    [CmdletBinding()] param([string] $Chord, [scriptblock] $ScriptBlock, [string] $ViMode = '')
    $global:CompletionHandlers[$ViMode + $Chord] = $ScriptBlock
}
__SCRIPT__
function Send-TestFrame([string] $Digits, [string] $Mode = '', [string] $Operation = 'e') {
    & $global:CompletionHandlers[$Mode + 'F12,q']
    foreach ($digit in $Digits.ToCharArray()) {
        $handler = $global:CompletionHandlers[$Mode + 'F12,' + $digit]
        if ($null -eq $handler) {
            global:__KetraTermCompletionAppend $digit
        } else {
            & $handler ([pscustomobject]@{ KeyChar = $digit }) $null
        }
    }
    & $global:CompletionHandlers[$Mode + 'F12,' + $Operation]
}
function Get-KetraNativeWidget { param([ValidateSet('alpha','beta')][string] $Mode) }
function Get-KetraCustomCompleter { param([string] $Value) }
function Get-KetraMutatingCompleter { param([string] $Value) }
Set-Alias knw Get-KetraNativeWidget
Set-Alias '0000' Get-KetraNativeWidget
Set-Alias '00 space' Get-KetraNativeWidget
Register-ArgumentCompleter -CommandName Get-KetraCustomCompleter -ParameterName Value -ScriptBlock {
    if ($ProgressPreference -ne 'SilentlyContinue') { throw 'Completion must suppress progress rendering' }
    [System.Management.Automation.CompletionResult]::new("'native value'", 'native value',
        [System.Management.Automation.CompletionResultType]::ParameterValue, 'native detail')
}
Register-ArgumentCompleter -CommandName Get-KetraMutatingCompleter -ParameterName Value -ScriptBlock {
    param($commandName, $parameterName, $wordToComplete)
    if ($wordToComplete -eq 'cursor') {
        [Microsoft.PowerShell.PSConsoleReadLine]::Cursor = [Microsoft.PowerShell.PSConsoleReadLine]::Line.Length - 1
    } else {
        [Microsoft.PowerShell.PSConsoleReadLine]::Line = [Microsoft.PowerShell.PSConsoleReadLine]::Line.ToUpperInvariant()
        if ($wordToComplete -eq 'throws') { throw 'Completer failed after mutating the editor' }
    }
    [System.Management.Automation.CompletionResult]::new('candidate', 'candidate',
        [System.Management.Automation.CompletionResultType]::ParameterValue, 'candidate')
}
function Complete-Test([string] $Line, [int] $Cursor = -1) {
    [Microsoft.PowerShell.PSConsoleReadLine]::Line = $Line
    [Microsoft.PowerShell.PSConsoleReadLine]::Cursor = $Cursor
    $global:TestRequestId++
    Send-TestFrame $global:TestRequestId.ToString('D19', [Globalization.CultureInfo]::InvariantCulture)
    [Console]::WriteLine()
}
$global:LASTEXITCODE = 77
$global:ProgressPreference = 'Continue'
$global:TestRequestId = 0L
global:__KetraTermCompletionReady
[Console]::WriteLine()
Complete-Test 'Get-KetraNativeWi'
Complete-Test 'Get-KetraNativeWidget -Mode a'
Complete-Test 'knw -Mode b'
Complete-Test 'Get-KetraCustomCompleter -Value n'
Complete-Test ''
if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
    $nativePath = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $nativeName = [IO.Path]::GetFileName($nativePath)
    $shortName = [IO.Path]::GetFileNameWithoutExtension($nativeName)
    $env:PATH = [IO.Path]::GetDirectoryName($nativePath) + [IO.Path]::PathSeparator + $env:PATH
    [Console]::WriteLine('APPLICATION=' + $nativeName + ';' + $shortName)
    Complete-Test $shortName
    Set-Item -LiteralPath ('Function:global:' + $shortName) -Value { }
    Complete-Test $shortName
    Set-Alias knativeapp $nativePath
    Complete-Test 'knativeapp -n'
    Complete-Test ("& '" + $nativePath + "' -n")
    Complete-Test ('Write-Output x | knativeapp -n')
    $nested = 'Write-Output $(knativeapp -n)'
    Complete-Test $nested ($nested.Length - 1)
    Complete-Test $nested
    Complete-Test '$unknownCommand -n'
}
# Make alias-collision cases independent of platform-specific default aliases.
Set-Alias ls Get-ChildItem
Set-Alias cat Get-Content
Set-Alias mkdir New-Item
Complete-Test 'ls --a'
Complete-Test 'ls -H'
Complete-Test 'cat -Enc u'
Complete-Test 'cat -Encoding:u'
Complete-Test 'mkdir -p:'
Complete-Test 'Get-KetraMutatingCompleter -Value text'
Complete-Test 'Get-KetraMutatingCompleter -Value cursor'
Complete-Test 'Get-KetraMutatingCompleter -Value throws'
[Console]::WriteLine("EXIT=$global:LASTEXITCODE")
if ($global:ProgressPreference -ne 'Continue') { throw 'Completion changed the interactive progress preference' }

function Get-KetraBufferOnly { param([string] $Value) }
$global:BufferOnlyCompleterCalls = 0
Register-ArgumentCompleter -CommandName Get-KetraBufferOnly -ParameterName Value -ScriptBlock {
    $global:BufferOnlyCompleterCalls++
    [System.Management.Automation.CompletionResult]::new('buffer-candidate', 'buffer-candidate',
        [System.Management.Automation.CompletionResultType]::ParameterValue, 'buffer-candidate')
}
[Microsoft.PowerShell.PSConsoleReadLine]::Line = 'Get-KetraBufferOnly -Value b'
[Microsoft.PowerShell.PSConsoleReadLine]::Cursor = -1
$global:TestRequestId++
Send-TestFrame $global:TestRequestId.ToString('D19', [Globalization.CultureInfo]::InvariantCulture) '' 'b'
[Console]::WriteLine()
if ($global:BufferOnlyCompleterCalls -ne 0) { throw 'Buffer confirmation invoked the candidate completer' }
Complete-Test 'Get-KetraBufferOnly -Value b'
if ($global:BufferOnlyCompleterCalls -ne 1) { throw 'A full request must still invoke the candidate completer' }
[Console]::WriteLine('BUFFER_ONLY=OK')

# Framing never calls the completer for incomplete, malformed, consumed, or overflowed tokens.
$savedCompleter = (Get-Item Function:\__KetraTermComplete).ScriptBlock
$global:ReceivedTokens = [Collections.Generic.List[long]]::new()
function global:__KetraTermComplete([long] $RequestId) { $global:ReceivedTokens.Add($RequestId) }
foreach ($invalid in @('', '1', ('0' * 19), ('1' * 20), '9223372036854775808', '00000000000000000-1')) {
    Send-TestFrame $invalid
}
& $global:CompletionHandlers['F12,e']
& $global:CompletionHandlers['F12,1'] ([pscustomobject]@{ KeyChar = '1' }) $null
& $global:CompletionHandlers['F12,e']
& $global:CompletionHandlers['F12,q']
foreach ($digit in '0000000000000000101'.ToCharArray()) { global:__KetraTermCompletionAppend $digit }
global:__KetraTermCompletionReady
& $global:CompletionHandlers['F12,e']
if ($global:ReceivedTokens.Count -ne 0) { throw 'Invalid or retired frame invoked completion' }
Send-TestFrame '0000000000000000101'
& $global:CompletionHandlers['F12,e']
Send-TestFrame '9223372036854775807'
if ($global:ReceivedTokens.Count -ne 2 -or $global:ReceivedTokens[0] -ne 101 -or $global:ReceivedTokens[1] -ne [long]::MaxValue) {
    throw 'Framed completion lost request identity or replayed a consumed token'
}
# PSReadLine resets bindings even when Set-PSReadLineOption selects the current EditMode.
$global:CompletionHandlers.Clear()
global:__KetraTermCompletionReady
foreach ($suffix in @('q', 'e', 'r', 'b') + (0..9)) {
    if (-not $global:CompletionHandlers.ContainsKey('F12,' + $suffix)) { throw 'Same-mode reset lost a private handler' }
}
Send-TestFrame '0000000000000000303'
if ($global:ReceivedTokens.Count -ne 3 -or $global:ReceivedTokens[2] -ne 303) {
    throw 'Same-mode reset lost the supplied request token'
}
$global:TestEditMode = 'Vi'
global:__KetraTermCompletionReady
foreach ($mode in @('Insert', 'Command')) {
    foreach ($suffix in @('q', 'e', 'r', 'b') + (0..9)) {
        if (-not $global:CompletionHandlers.ContainsKey($mode + 'F12,' + $suffix)) { throw 'Missing Vi mode handler' }
    }
    Send-TestFrame '0000000000000000202' $mode
}
if ($global:ReceivedTokens.Count -ne 5 -or $global:ReceivedTokens[3] -ne 202 -or $global:ReceivedTokens[4] -ne 202) {
    throw 'Vi handler installation lost the supplied request token'
}
Set-Item Function:\global:__KetraTermComplete $savedCompleter
global:__KetraTermComplete
[Console]::WriteLine('FRAMING=OK')

