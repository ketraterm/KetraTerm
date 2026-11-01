# Color customization

Configure an immutable `SwingSettings` snapshot on the EDT. Return the new snapshot
from the view's settings provider and call `reloadSettings()`. View color settings
do not rewrite stored cell attributes. The [configuration guide](../../docs/library/configuration.md)
explains session and host ownership.

## Selection

`selectionStyle` configures focused selection. `inactiveSelectionStyle = null`
inherits that complete style; supplying a style configures the unfocused view
independently. Focus changes repaint the view without changing the selection.

```kotlin
val settings = SwingSettings.create {
    it.selectionStyle = SwingSelectionStyle(
        background = 0x804B6EAF.toInt(),
        foreground = 0xFFFFFFFF.toInt(),
    )
    // Preserve terminal/link/search text colors in the unfocused view.
    it.inactiveSelectionStyle = SwingSelectionStyle(background = 0x404B6EAF)
}
```

| Value | Meaning |
| --- | --- |
| `background` | Exact ARGB fill, blended over the existing cell/search/link background. Zero alpha leaves that background visible. Default: `0x66FFFFFF`. |
| `foreground = null` | Preserve terminal/link/search text presentation. |
| Opaque `foreground` | Replace selected text color. Transparent text overrides are rejected. |
| `reverseVideo = true` | Swap each selected cell's original terminal foreground/background after inverse attributes. Configured foreground/background are unused in this mode. Images are not inverted. |

Use `SwingSelectionStyle(reverseVideo = true)` for cell-based reverse selection.
Linear/block selections, bidirectional rows, wide cells, and graphemes share the
view's normal selection geometry. Concealed and blink-hidden text stays hidden.

`selectionBackground` remains a convenience getter/setter for
`selectionStyle.background`; there is one stored style. Setting this convenience
property preserves the style's foreground and reverse-video choice. For example,
changing a reversed selection's fill requires first disabling `reverseVideo`.

## Search matches

`searchMatchStyle` and `searchActiveMatchStyle` independently configure ordinary
matches and the active navigation result. Each immutable `SwingSearchMatchStyle`
has a required background and optional foreground and border. The active style
does not inherit any roles from the ordinary style.

```kotlin
val settings = SwingSettings.create {
    it.searchMatchStyle = SwingSearchMatchStyle(
        background = 0x554B6EAF,
        border = 0xFF4B6EAF.toInt(),
    )
    it.searchActiveMatchStyle = SwingSearchMatchStyle(
        background = 0xFFFFC857.toInt(),
        foreground = 0xFF161616.toInt(),
        border = 0xFFFFFFFF.toInt(),
    )
}
```

| Value | Meaning |
| --- | --- |
| `background` | Exact ARGB fill blended over terminal/link backgrounds. Zero alpha gives a border-only or text-only highlight. |
| `foreground = null` | Preserve underlying terminal/link text, including faint presentation. |
| Opaque `foreground` | Replace matched text color before selection and minimum contrast. Transparent text overrides are rejected. |
| `border = null` | No outline. A supplied ARGB color adds a one-logical-pixel outline inside each visible contiguous match segment; zero alpha is invisible. |

Wrapped matches have an outline on each physical row. Bidirectional matches may
split into separate visual segments; each segment has its own outline. Borders
use the same cell geometry as the fill, consume no layout space, and never extend
into neighboring cells. Selection, glyphs, and the cursor paint over search
borders. Concealed and blink-hidden text stays hidden; intrinsic color emoji and
images are not recolored.

Defaults retain the existing background-only appearance: normal `0x55FFD54F`,
active `0xAAFF8C00`, both foregrounds and borders null. Existing
`searchMatchBackground` and `searchActiveMatchBackground` accessors remain
convenience aliases for their style's background. Assigning either preserves
that style's foreground and border. Reloading search styles repaints existing
results without changing the query, active match, or session palette.

## Prompt decorations

Choose `promptDecoration = SwingPromptDecoration.GUTTER`, `DIVIDER`, or `NONE`.
Decorations require shell metadata and appear only on the primary screen.
Color overrides belong to `SwingSettings`; reload a new snapshot on the EDT to
repaint existing decorations without changing shell records or grid dimensions.

```kotlin
val settings = SwingSettings.create {
    it.promptDecoration = SwingPromptDecoration.GUTTER
    it.shellIntegrationPromptDotHoverColor = 0xFFFFFFFF.toInt()
    it.shellIntegrationFailedPromptDotHoverColor = 0xFFFF8A8A.toInt()
    it.shellIntegrationPromptDotHoverHaloColor = 0x404B6EAF
    it.shellIntegrationFailedPromptDotHoverHaloColor = 0x40E74856
    // Used when promptDecoration is DIVIDER.
    it.shellIntegrationPromptDividerColor = 0x304B6EAF
    it.shellIntegrationPromptDividerHoverColor = 0xFF4B6EAF.toInt()
}
```

All six overrides accept packed ARGB values or null. Alpha is used as supplied;
zero alpha hides that part of the decoration. Null restores these defaults:

| Override | Null behavior |
| --- | --- |
| `shellIntegrationPromptDotHoverColor` | Retain the normal prompt dot's resolved color. |
| `shellIntegrationFailedPromptDotHoverColor` | Retain `shellIntegrationFailedPromptDotColor`. |
| `shellIntegrationPromptDotHoverHaloColor` | Use the hovered normal dot's RGB with alpha `0x52`. |
| `shellIntegrationFailedPromptDotHoverHaloColor` | Use the hovered failed dot's RGB with alpha `0x52`. |
| `shellIntegrationPromptDividerColor` | Use the current terminal default foreground's RGB with alpha `0x30`. |
| `shellIntegrationPromptDividerHoverColor` | Independently use the current terminal default foreground's RGB with alpha `0x70`. This does not inherit the normal divider override. |

Dots paint over their hover halos, with their alphas composed normally. Set both
halo overrides to `0` to retain dot color changes without halos. A transparent
hover dot alone does not suppress its default halo; configure both roles to hide
both. Normal and failed colors are independent; marker hover never recolors a
failed-command rail. Normal-state dots and rails keep their existing
`shellIntegrationPromptDotColor`, `shellIntegrationFailedPromptDotColor`, and
`shellIntegrationFailedCommandRailColor` settings. The established normal-dot
default `0x8CFFFFFF` requests terminal foreground RGB with alpha `0x8C`.

These colors do not affect terminal text or participate in `minimumContrastRatio`.
Palette-derived defaults follow the current session foreground, including
permitted terminal palette changes. Explicit overrides stay fixed. Transparent
colors retain the decoration's hit target and space: a hidden divider rule still
reserves its band. Use `promptDecoration = NONE` to remove decoration space, or
`shellIntegrationPromptDotsVisible = false` to suppress dots and their halos while
retaining any configured failure rails. Hover follows the full existing gutter
or divider hit target, not just the painted dot or rule.

## Search controls

The optional host search bar uses a separate immutable `SwingTerminalSearchColors`
snapshot. It supports independent failure/no-match counter and query-border colors,
plus query-field selection background and foreground. Configure those roles with
`SwingTerminalSearchBar.refreshColors`, not `SwingSettings`. See
[search chrome colors and defaults](../../ketraterm-ui-swing-host/README.md#search-colors).

## Terminal overrides and precedence

Host styles are presentation defaults. Permitted OSC 21 selection overrides in
the session palette take precedence independently for foreground and background,
in both focused and unfocused views. Dynamic overrides use original cell colors.
Resetting those roles returns to the applicable host style. Palette fallback
fields without explicit selection-override flags do not replace host styles.
The [protocol contract](../../docs/reference/protocol.md#kitty-colors)
describes permissions, queries, and reset behavior.

Cell/link backgrounds are painted first, followed by search fills and borders,
selection fills, text, and the cursor. Text color resolves from terminal/link
styling, then an explicit search foreground, then a selection foreground override,
then enabled minimum contrast. A null selection foreground preserves the
terminal/link/search text. Application-supplied underline colors remain
authoritative.

## Contrast and faint text

`minimumContrastRatio` accepts finite values from `1.0` through `21.0`.
The default `1.0` disables adjustment, preserving requested text colors. Set it
to `4.5` for the WCAG normal-text contrast target, or `3.0` to request the earlier
terminal target. This is a rendering aid, not a claim that an embedding application
meets every accessibility requirement.

The renderer evaluates the displayed foreground against the cell background after
inverse attributes, link fills, search highlights, and selection fills. It shifts
text toward black or white only when needed. Some targets cannot be reached by
changing foreground alone; in that case it uses whichever of black or white gives
the greatest contrast. Backgrounds and stored terminal colors never change.
OSC color queries therefore still report the session's original palette values.

`faintOpacity` accepts finite values from `0.0` through `1.0`, defaulting to `0.5`.
SGR faint text blends toward its displayed background: zero reaches that background,
half blends equally, and one disables dimming. Explicit selection/search/link foregrounds
replace this inherited faint presentation. Contrast adjustment runs afterwards;
when enabled it can brighten or darken faint text to restore readability.

```kotlin
val settings = SwingSettings.create {
    it.minimumContrastRatio = 4.5
    it.faintOpacity = 0.5
}
val exactColors = settings.copy { it.minimumContrastRatio = 1.0 }
```

Configured selection/search colors and permitted terminal selection overrides are inputs
to the same opt-in contrast policy. Disable contrast adjustment when their exact
foreground colors must be retained. Concealed/blink-hidden text remains hidden.
Box drawing, block elements, geometric shading, and braille retain their color
relationships; intrinsic color emoji and image pixels are not recolored. Cursor
colors and explicit underline colors retain their separate presentation rules.
Contrast uses the terminal cell background and UI fills. It does not sample image
pixels, a host image, or translucent window composition behind the terminal.

Color policy and contrast caches belong to each renderer, with bounded primitive
storage. Frame installation reserves reusable row scratch before component painting.
Stable repainting does not allocate color-policy entries; Java2D color-cache misses,
glyph preparation, and native rendering have their own allocation behavior.

## Migration

Selection backgrounds are now exact: `0x66FFFFFF` no longer requests a palette
fallback, and low-visibility fills are never silently substituted. Hosts that
previously relied on automatic palette-derived selection must explicitly choose
their theme's selection background and desired alpha. Existing `selectionBackground`
source and binary entry points remain available.

Ordinary text is no longer silently adjusted to 3:1. Set `minimumContrastRatio = 3.0`
to retain that target, or choose `4.5` for stronger readability. Faint text now blends
toward its displayed background rather than always darkening RGB toward black.
This intentionally improves faint text on light themes. Selection and color-policy
changes are local UI settings; reloading them alone preserves application palette
overrides. A deliberate host palette change follows the existing session palette
replacement contract.
