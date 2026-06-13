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

function Format-IntArray {
    param(
        [string] $Name,
        [System.Collections.Generic.List[object]] $Ranges,
        [string] $Indent = "    ",
        [string] $Visibility = "private"
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add("${Indent}${Visibility} val ${Name}: IntArray =") | Out-Null
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

$graphemeProperties = @(
    "CR",
    "LF",
    "Control",
    "Extend",
    "ZWJ",
    "Regional_Indicator",
    "Prepend",
    "L",
    "V",
    "T",
    "LV",
    "LVT",
    "SpacingMark"
)

$graphemeRanges = @{}
foreach ($property in $graphemeProperties) {
    $graphemeRanges[$property] = Read-NamedPropertyRanges $graphemePath $property
}
$extendedPictographicRanges = Read-NamedPropertyRanges $emojiPath "Extended_Pictographic"

$parserParts = [System.Collections.Generic.List[string]]::new()
$parserParts.Add($copyright.TrimEnd()) | Out-Null
$parserParts.Add("package io.github.jvterm.parser.unicode") | Out-Null
$parserParts.Add("") | Out-Null
$parserParts.Add("/**") | Out-Null
$parserParts.Add(" * Unicode $UnicodeVersion grapheme break and emoji property table generated from UAX #29 data.") | Out-Null
$parserParts.Add(" * Regenerate with tools/generate-unicode-tables.ps1 after upgrading Unicode data.") | Out-Null
$parserParts.Add(" */") | Out-Null
$parserParts.Add("internal object GeneratedGraphemeBreakTable {") | Out-Null
foreach ($property in $graphemeProperties) {
    $name = ($property -replace "_", "_").ToUpperInvariant() + "_RANGES"
    $parserParts.Add((Format-IntArray $name $graphemeRanges[$property])) | Out-Null
    $parserParts.Add("") | Out-Null
}
$parserParts.Add((Format-IntArray "EXTENDED_PICTOGRAPHIC_RANGES" $extendedPictographicRanges)) | Out-Null
$parserParts.Add("") | Out-Null
$parserParts.Add(@"
    @JvmStatic
    fun graphemeBreakClass(codepoint: Int): Int =
        when {
            contains(CR_RANGES, codepoint) -> UnicodeClass.GRAPHEME_CR
            contains(LF_RANGES, codepoint) -> UnicodeClass.GRAPHEME_LF
            contains(CONTROL_RANGES, codepoint) -> UnicodeClass.GRAPHEME_CONTROL
            contains(PREPEND_RANGES, codepoint) -> UnicodeClass.GRAPHEME_PREPEND
            contains(ZWJ_RANGES, codepoint) -> UnicodeClass.GRAPHEME_ZWJ
            contains(REGIONAL_INDICATOR_RANGES, codepoint) -> UnicodeClass.GRAPHEME_REGIONAL_INDICATOR
            contains(L_RANGES, codepoint) -> UnicodeClass.GRAPHEME_L
            contains(V_RANGES, codepoint) -> UnicodeClass.GRAPHEME_V
            contains(T_RANGES, codepoint) -> UnicodeClass.GRAPHEME_T
            contains(LV_RANGES, codepoint) -> UnicodeClass.GRAPHEME_LV
            contains(LVT_RANGES, codepoint) -> UnicodeClass.GRAPHEME_LVT
            contains(EXTEND_RANGES, codepoint) -> UnicodeClass.GRAPHEME_EXTEND
            contains(SPACINGMARK_RANGES, codepoint) -> UnicodeClass.GRAPHEME_SPACING_MARK
            else -> UnicodeClass.GRAPHEME_OTHER
        }

    @JvmStatic
    fun isExtendedPictographic(codepoint: Int): Boolean = contains(EXTENDED_PICTOGRAPHIC_RANGES, codepoint)

    private fun contains(
        ranges: IntArray,
        codepoint: Int,
    ): Boolean {
        var low = 0
        var high = (ranges.size / 2) - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]
            if (codepoint < start) {
                high = mid - 1
            } else if (codepoint > end) {
                low = mid + 1
            } else {
                return true
            }
        }

        return false
    }
}
"@.TrimEnd()) | Out-Null

$parserTarget = Join-Path $Root "terminal-parser/src/main/kotlin/parser/unicode/GeneratedGraphemeBreakTable.kt"
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

$bitsetLimit = 0x20000
$wideSplit = Split-Ranges $wideRanges $bitsetLimit
$zeroSplit = Split-Ranges $zeroRanges $bitsetLimit
$ambiguousSplit = Split-Ranges $ambiguousRanges $bitsetLimit
$terminalCellGraphicSplit = Split-Ranges $terminalCellGraphicRanges $bitsetLimit
$emojiVariationBaseSplit = Split-Ranges $emojiVariationBaseRanges $bitsetLimit

$coreParts = [System.Collections.Generic.List[string]]::new()
$coreParts.Add($copyright.TrimEnd()) | Out-Null
$coreParts.Add("package io.github.jvterm.core.util") | Out-Null
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

$coreTarget = Join-Path $Root "terminal-core/src/main/kotlin/core/util/GeneratedUnicodeWidthTable.kt"
Write-Utf8NoBom $coreTarget ([string]::Join([Environment]::NewLine, $coreParts) + [Environment]::NewLine)
