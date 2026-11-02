# Replace only the line-editor boundary; use the real PowerShell completion engine.
Add-Type @'
namespace Microsoft.PowerShell {
    public static class PSConsoleReadLine {
        public static string Line;
        public static void GetBufferState(ref string line, ref int cursor) {
            line = Line;
            cursor = Line.Length;
        }
    }
}
'@
function global:Get-Module { param([string] $Name) [pscustomobject]@{ Name = 'PSReadLine' } }
function global:Set-PSReadLineKeyHandler {
    [CmdletBinding()] param([string] $Chord, [scriptblock] $ScriptBlock)
    $global:CompletionHandler = $ScriptBlock
}
__SCRIPT__
function Get-KetraNativeWidget { param([ValidateSet('alpha','beta')][string] $Mode) }
function Get-KetraCustomCompleter { param([string] $Value) }
Set-Alias knw Get-KetraNativeWidget
Register-ArgumentCompleter -CommandName Get-KetraCustomCompleter -ParameterName Value -ScriptBlock {
    [System.Management.Automation.CompletionResult]::new("'native value'", 'native value',
        [System.Management.Automation.CompletionResultType]::ParameterValue, 'native detail')
}
function Complete-Test([string] $Line) {
    [Microsoft.PowerShell.PSConsoleReadLine]::Line = $Line
    & $global:CompletionHandler
    [Console]::WriteLine()
}
$global:LASTEXITCODE = 77
global:__KetraTermCompletionReady
[Console]::WriteLine()
Complete-Test 'Get-KetraNativeWi'
Complete-Test 'Get-KetraNativeWidget -Mode a'
Complete-Test 'knw -Mode b'
Complete-Test 'Get-KetraCustomCompleter -Value n'
Complete-Test ''
[Console]::WriteLine("EXIT=$global:LASTEXITCODE")

