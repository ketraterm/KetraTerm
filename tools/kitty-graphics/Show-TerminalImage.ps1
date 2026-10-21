<#
.SYNOPSIS
Displays a PNG image in KetraTerm using the Kitty graphics protocol.

.DESCRIPTION
Run directly from PowerShell inside KetraTerm. Sends inline PNG data in
4096-character Base64 chunks, without WSL, kitten, or native PTY size queries.
Terminal replies are suppressed; this script does not confirm successful display.

.PARAMETER Path
Literal path to the PNG file.

.PARAMETER Columns
Display width in terminal columns. Height preserves the image's aspect ratio.

.EXAMPLE
./Show-TerminalImage.ps1 'C:\Pictures\example.png' -Columns 25
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [string] $Path,

    [ValidateRange(1, 65535)]
    [int] $Columns = 40
)

$ErrorActionPreference = 'Stop'

$file = Get-Item -LiteralPath $Path
$bytes = [System.IO.File]::ReadAllBytes($file.FullName)

if ($bytes.Length -lt 8 -or
    [System.BitConverter]::ToString($bytes, 0, 8) -ne '89-50-4E-47-0D-0A-1A-0A') {
    throw 'The file must be a PNG image.'
}

$payload = [System.Convert]::ToBase64String($bytes)
$escape = [char]27
$chunkSize = 4096

for ($offset = 0; $offset -lt $payload.Length; $offset += $chunkSize) {
    $length = [Math]::Min($chunkSize, $payload.Length - $offset)
    $more = [int](($offset + $length) -lt $payload.Length)

    if ($offset -eq 0) {
        $header = "a=T,f=100,t=d,c=$Columns,q=2,m=$more"
    }
    else {
        $header = "q=2,m=$more"
    }

    $chunk = $payload.Substring($offset, $length)
    [Console]::Write("${escape}_G${header};${chunk}${escape}\")
}

[Console]::WriteLine()
