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
    $global:CompletionHandler = $ScriptBlock
}
__SCRIPT__
function Get-KetraNativeWidget { param([ValidateSet('alpha','beta')][string] $Mode) }
function Get-KetraCustomCompleter { param([string] $Value) }
Set-Alias knw Get-KetraNativeWidget
Set-Alias '0000' Get-KetraNativeWidget
Set-Alias '00 space' Get-KetraNativeWidget
Register-ArgumentCompleter -CommandName Get-KetraCustomCompleter -ParameterName Value -ScriptBlock {
    [System.Management.Automation.CompletionResult]::new("'native value'", 'native value',
        [System.Management.Automation.CompletionResultType]::ParameterValue, 'native detail')
}
function Complete-Test([string] $Line, [int] $Cursor = -1) {
    [Microsoft.PowerShell.PSConsoleReadLine]::Line = $Line
    [Microsoft.PowerShell.PSConsoleReadLine]::Cursor = $Cursor
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
[Console]::WriteLine("EXIT=$global:LASTEXITCODE")

