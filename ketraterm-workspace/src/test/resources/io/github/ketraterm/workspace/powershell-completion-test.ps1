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
function global:Set-PSReadLineKeyHandler {
    [CmdletBinding()] param([string] $Chord, [scriptblock] $ScriptBlock)
    if ($Chord -eq 'F12,e') { $global:CompletionHandler = $ScriptBlock }
}
__SCRIPT__
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
    & $global:CompletionHandler
    [Console]::WriteLine()
}
$global:LASTEXITCODE = 77
$global:ProgressPreference = 'Continue'
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

