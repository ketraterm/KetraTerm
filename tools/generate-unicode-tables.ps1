param(
    [string] $UnicodeVersion = "17.0.0",
    [string] $Root = (Resolve-Path "$PSScriptRoot/..").Path
)

$ErrorActionPreference = "Stop"

$copyright = @"
/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"@

$dataDir = Join-Path $Root "build/unicode-data/$UnicodeVersion"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

function Get-UnicodeFile {
    param(
        [string] $Name,
        [string] $Url
    )

    $path = Join-Path $dataDir $Name
    if (-not (Test-Path $path)) {
        Invoke-WebRequest -Uri $Url -OutFile $path
    }
    return $path
}

function Add-Range {
    param(
        [System.Collections.Generic.List[object]] $Ranges,
        [int] $Start,
        [int] $End
    )

    $Ranges.Add([pscustomobject]@{ Start = $Start; End = $End }) | Out-Null
}

function Read-PropertyRanges {
    param(
        [string] $Path,
        [scriptblock] $Accept
    )

    $ranges = [System.Collections.Generic.List[object]]::new()
    foreach ($line in Get-Content $Path) {
        $body = ($line -split "#", 2)[0].Trim()
        if ($body.Length -eq 0) {
            continue
        }

        $parts = $body -split ";"
        $rangeText = $parts[0].Trim()
        $propValue = $parts[1].Trim()
        if (-not (& $Accept $propValue)) {
            continue
        }

        if ($rangeText.Contains("..")) {
            $bounds = $rangeText -split "\.\."
            $start = [Convert]::ToInt32($bounds[0], 16)
            $end = [Convert]::ToInt32($bounds[1], 16)
        } else {
            $start = [Convert]::ToInt32($rangeText, 16)
            $end = $start
        }
        Add-Range $ranges $start $end
    }
    return Merge-Ranges $ranges
}

function Read-NamedPropertyRanges {
    param(
        [string] $Path,
        [string] $TargetProperty
    )

    return Read-PropertyRanges $Path { param($p) $p -eq $TargetProperty }
}

function Merge-Ranges {
    param([System.Collections.Generic.List[object]] $Ranges)

    $merged = [System.Collections.Generic.List[object]]::new()
    foreach ($range in ($Ranges | Sort-Object Start, End)) {
        if ($merged.Count -eq 0) {
            Add-Range $merged $range.Start $range.End
            continue
        }

        $last = $merged[$merged.Count - 1]
        if ($range.Start -le ($last.End + 1)) {
            if ($range.End -gt $last.End) {
                $last.End = $range.End
            }
        } else {
            Add-Range $merged $range.Start $range.End
        }
    }
    return $merged
}

function Split-Ranges {
    param(
        [System.Collections.Generic.List[object]] $Ranges,
        [int] $Limit
    )

    $low = [System.Collections.Generic.List[object]]::new()
    $high = [System.Collections.Generic.List[object]]::new()
    foreach ($range in $Ranges) {
        if ($range.Start -lt $Limit) {
            Add-Range $low $range.Start ([Math]::Min($range.End, $Limit - 1))
        }
        if ($range.End -ge $Limit) {
            Add-Range $high ([Math]::Max($range.Start, $Limit)) $range.End
        }
    }
    return @{
        Low = Merge-Ranges $low
        High = Merge-Ranges $high
    }
}

function Subtract-Ranges {
    param(
        [System.Collections.Generic.List[object]] $Ranges,
        [System.Collections.Generic.List[object]] $Excluded
    )

    $result = [System.Collections.Generic.List[object]]::new()
    $sortedExcluded = $Excluded | Sort-Object Start, End

    foreach ($range in ($Ranges | Sort-Object Start, End)) {
        $cursor = $range.Start
        foreach ($excludedRange in $sortedExcluded) {
            if ($excludedRange.End -lt $cursor) {
                continue
            }
            if ($excludedRange.Start -gt $range.End) {
                break
            }

            if ($excludedRange.Start -gt $cursor) {
                Add-Range $result $cursor ([Math]::Min($range.End, $excludedRange.Start - 1))
            }

            $cursor = [Math]::Max($cursor, $excludedRange.End + 1)
            if ($cursor -gt $range.End) {
                break
            }
        }

        if ($cursor -le $range.End) {
            Add-Range $result $cursor $range.End
        }
    }

    return Merge-Ranges $result
}

function Format-IntArray {
    param(
        [string] $Name,
        [System.Collections.Generic.List[object]] $Ranges,
        [string] $Indent = "    ",
        [string] $Visibility = "private"
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("${Indent}${Visibility} val ${Name}: IntArray =") | Out-Null
    if ($Ranges.Count -eq 0) {
        $lines.Add("${Indent}    intArrayOf()") | Out-Null
        return [string]::Join([Environment]::NewLine, $lines)
    }

    $lines.Add("${Indent}    intArrayOf(") | Out-Null
    foreach ($range in $Ranges) {
        $startHex = "0x{0:X}" -f $range.Start
        $endHex = "0x{0:X}" -f $range.End
        $lines.Add("${Indent}        $startHex,") | Out-Null
        $lines.Add("${Indent}        $endHex,") | Out-Null
    }
    $lines.Add("${Indent}    )") | Out-Null
    return [string]::Join([Environment]::NewLine, $lines)
}

function Write-Utf8NoBom {
    param(
        [string] $Path,
        [string] $Content
    )

    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Read-EmojiVariationBases {
    param([string] $Path)

    $bases = [System.Collections.Generic.List[object]]::new()
    foreach ($line in Get-Content $Path) {
        $body = ($line -split "#", 2)[0].Trim()
        if ($body.Length -eq 0) {
            continue
        }

        $parts = $body -split ";"
        if ($parts[1].Trim() -ne "emoji style") {
            continue
        }

        $sequence = $parts[0].Trim() -split "\s+"
        $base = [Convert]::ToInt32($sequence[0], 16)
        Add-Range $bases $base $base
    }

    return Merge-Ranges $bases
}

$ucdBase = "https://www.unicode.org/Public/$UnicodeVersion/ucd"
$emojiBase = "https://www.unicode.org/Public/$UnicodeVersion/ucd/emoji"

$graphemePath = Get-UnicodeFile "GraphemeBreakProperty.txt" "$ucdBase/auxiliary/GraphemeBreakProperty.txt"
$emojiPath = Get-UnicodeFile "emoji-data.txt" "$emojiBase/emoji-data.txt"
$emojiVariationSequencesPath =
    Get-UnicodeFile "emoji-variation-sequences.txt" "$emojiBase/emoji-variation-sequences.txt"
$eastAsianWidthPath = Get-UnicodeFile "EastAsianWidth.txt" "$ucdBase/EastAsianWidth.txt"
$derivedGeneralCategoryPath = Get-UnicodeFile "DerivedGeneralCategory.txt" "$ucdBase/extracted/DerivedGeneralCategory.txt"

# IDs are the packed representation consumed by UnicodeClass. Other is zero;
# bit 4 is Extended_Pictographic. Keep this mapping aligned with UnicodeClass.
$graphemeIds = [ordered]@{
    CR = 1; LF = 2; Control = 3; Extend = 4; ZWJ = 5
    Regional_Indicator = 6; SpacingMark = 7; Prepend = 8
    L = 9; V = 10; T = 11; LV = 12; LVT = 13
}
$properties = [byte[]]::new(0x110000)
foreach ($property in $graphemeIds.Keys) {
    foreach ($range in (Read-NamedPropertyRanges $graphemePath $property)) {
        for ($cp = $range.Start; $cp -le $range.End; $cp++) {
            $properties[$cp] = $graphemeIds[$property]
        }
    }
}
foreach ($range in (Read-NamedPropertyRanges $emojiPath 'Extended_Pictographic')) {
    for ($cp = $range.Start; $cp -le $range.End; $cp++) {
        $properties[$cp] = $properties[$cp] -bor 0x10
    }
}

# 128-codepoint blocks minimize the combined one-byte index/data footprint for
# Unicode 17. Deduplicate at generation time, never during class initialization.
$blockShift = 7
$blockSize = 1 -shl $blockShift
$blockIds = [System.Collections.Generic.Dictionary[string, int]]::new([StringComparer]::Ordinal)
$index = [System.Collections.Generic.List[int]]::new()
$data = [System.Collections.Generic.List[int]]::new()
for ($start = 0; $start -lt $properties.Length; $start += $blockSize) {
    $key = [Convert]::ToBase64String($properties, $start, $blockSize)
    if (-not $blockIds.ContainsKey($key)) {
        $blockIds[$key] = $blockIds.Count
        for ($offset = 0; $offset -lt $blockSize; $offset++) {
            $data.Add($properties[$start + $offset])
        }
    }
    $index.Add($blockIds[$key])
}

function Format-PropertyString {
    param([string] $Name, [System.Collections.Generic.List[int]] $Values)

    # JVM compact strings retain these Latin-1 values in immutable byte storage.
    # The offset avoids control characters; escaped literals need no runtime
    # decoder, temporary arrays, or large JVM array-initialization methods.
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("    private const val ${Name}: String =")
    for ($start = 0; $start -lt $Values.Count; $start += $blockSize) {
        $literal = [System.Text.StringBuilder]::new()
        for ($i = $start; $i -lt [Math]::Min($start + $blockSize, $Values.Count); $i++) {
            if ($Values[$i] -gt 191) {
                throw 'Property table exceeds the compact Latin-1 encoding; revisit block size.'
            }
            [void] $literal.Append(('\u{0:X4}' -f ($Values[$i] + 0x40)))
        }
        $suffix = if ($start + $blockSize -lt $Values.Count) { ' +' } else { '' }
        $indent = if ($start -eq 0) { '        ' } else { '            ' }
        $lines.Add($indent + '"' + $literal.ToString() + '"' + $suffix)
    }
    return [string]::Join([Environment]::NewLine, $lines)
}

$parserParts = [System.Collections.Generic.List[string]]::new()
$parserParts.Add($copyright.TrimEnd())
$parserParts.Add('// One literal per Unicode block keeps generated source and formatter work bounded.')
$parserParts.Add('@file:Suppress("ktlint:standard:max-line-length")')
$parserParts.Add('')
$parserParts.Add('package io.github.ketraterm.parser.unicode')
$parserParts.Add(@"

/**
 * Unicode $UnicodeVersion grapheme break and Extended_Pictographic properties.
 * Regenerate with tools/generate-unicode-tables.ps1 after upgrading Unicode data.
 *
 * Two-stage lookup: $($index.Count) block indices and $($data.Count) packed properties.
 * Identical 128-codepoint blocks share storage. Latin-1 string constants use JVM
 * compact byte storage without runtime decoding or array initialization loops.
 */
internal object GeneratedGraphemeBreakTable {
    @JvmStatic
    fun properties(codepoint: Int): Int {
        if (codepoint !in 0..0x10FFFF) return UnicodeClass.GRAPHEME_OTHER
        val block = BLOCK_INDEX[codepoint ushr $blockShift].code - 0x40
        return BLOCK_DATA[(block shl $blockShift) + (codepoint and $($blockSize - 1))].code - 0x40
    }

"@.TrimEnd())
$parserParts.Add('')
$parserParts.Add((Format-PropertyString 'BLOCK_INDEX' $index))
$parserParts.Add('')
$parserParts.Add((Format-PropertyString 'BLOCK_DATA' $data))
$parserParts.Add('}')
$parserTarget = Join-Path $Root 'ketraterm-parser/src/main/kotlin/io/github/ketraterm/parser/unicode/GeneratedGraphemeBreakTable.kt'
Write-Utf8NoBom $parserTarget ([string]::Join([Environment]::NewLine, $parserParts) + [Environment]::NewLine)

$wideRanges = [System.Collections.Generic.List[object]]::new()
foreach ($range in (Read-PropertyRanges $eastAsianWidthPath { param($p) $p -eq "W" -or $p -eq "F" })) {
    Add-Range $wideRanges $range.Start $range.End
}
$emojiPresentationRanges = Read-NamedPropertyRanges $emojiPath "Emoji_Presentation"
foreach ($range in $emojiPresentationRanges) {
    Add-Range $wideRanges $range.Start $range.End
}
$wideRanges = Merge-Ranges $wideRanges
$emojiVariationBaseRanges = Read-EmojiVariationBases $emojiVariationSequencesPath

$zeroRanges = [System.Collections.Generic.List[object]]::new()
foreach ($range in (Read-PropertyRanges $derivedGeneralCategoryPath { param($p) $p -eq "Mn" -or $p -eq "Me" -or $p -eq "Cf" })) {
    Add-Range $zeroRanges $range.Start $range.End
}
Add-Range $zeroRanges 0x1160 0x11FF
$zeroRanges = Merge-Ranges $zeroRanges

$ambiguousRanges = Read-PropertyRanges $eastAsianWidthPath { param($p) $p -eq "A" }
$terminalCellGraphicRanges = [System.Collections.Generic.List[object]]::new()
Add-Range $terminalCellGraphicRanges 0x2500 0x257F
Add-Range $terminalCellGraphicRanges 0x2580 0x259F
Add-Range $terminalCellGraphicRanges 0x2800 0x28FF
Add-Range $terminalCellGraphicRanges 0x1FB00 0x1FBFF
$terminalCellGraphicRanges = Merge-Ranges $terminalCellGraphicRanges
$ambiguousRanges = Subtract-Ranges $ambiguousRanges $zeroRanges
$ambiguousRanges = Subtract-Ranges $ambiguousRanges $wideRanges
$ambiguousRanges = Subtract-Ranges $ambiguousRanges $terminalCellGraphicRanges

$bitsetLimit = 0x20000
$wideSplit = Split-Ranges $wideRanges $bitsetLimit
$zeroSplit = Split-Ranges $zeroRanges $bitsetLimit
$ambiguousSplit = Split-Ranges $ambiguousRanges $bitsetLimit
$terminalCellGraphicSplit = Split-Ranges $terminalCellGraphicRanges $bitsetLimit
$emojiVariationBaseSplit = Split-Ranges $emojiVariationBaseRanges $bitsetLimit

$coreParts = [System.Collections.Generic.List[string]]::new()
$coreParts.Add($copyright.TrimEnd()) | Out-Null
$coreParts.Add("package io.github.ketraterm.core.util") | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add("/**") | Out-Null
$coreParts.Add(" * Unicode $UnicodeVersion terminal width property table generated from UCD data.") | Out-Null
$coreParts.Add(" * Regenerate with tools/generate-unicode-tables.ps1 after upgrading Unicode data.") | Out-Null
$coreParts.Add(" */") | Out-Null
$coreParts.Add("internal object GeneratedUnicodeWidthTable {") | Out-Null
$coreParts.Add("    const val BITSET_LIMIT: Int = 0x20000") | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "WIDE_RANGES" $wideSplit.Low "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "WIDE_ASTRAL_RANGES" $wideSplit.High "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "ZERO_RANGES" $zeroSplit.Low "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "ZERO_ASTRAL_RANGES" $zeroSplit.High "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "AMBIGUOUS_RANGES" $ambiguousSplit.Low "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "AMBIGUOUS_ASTRAL_RANGES" $ambiguousSplit.High "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "TERMINAL_CELL_GRAPHIC_RANGES" $terminalCellGraphicSplit.Low "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "TERMINAL_CELL_GRAPHIC_ASTRAL_RANGES" $terminalCellGraphicSplit.High "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "EMOJI_VARIATION_BASE_RANGES" $emojiVariationBaseSplit.Low "    " "internal")) | Out-Null
$coreParts.Add("") | Out-Null
$coreParts.Add((Format-IntArray "EMOJI_VARIATION_BASE_ASTRAL_RANGES" $emojiVariationBaseSplit.High "    " "internal")) | Out-Null
$coreParts.Add("}") | Out-Null

$coreTarget = Join-Path $Root "ketraterm-core/src/main/kotlin/io/github/ketraterm/core/util/GeneratedUnicodeWidthTable.kt"
Write-Utf8NoBom $coreTarget ([string]::Join([Environment]::NewLine, $coreParts) + [Environment]::NewLine)
