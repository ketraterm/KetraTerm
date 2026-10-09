# Public API customization follow-up — 2026-10-09

Reviewed revision: `6912e2cd8406c80cd2515dcc5a2da7e5a353eaa1`.

This follow-up retains three concrete customization limits after API01–API07.
Evidence comes from public declarations and the published ABI at the reviewed
revision. Implementation bodies, old examples, and old repository documentation
do not establish these findings. Comparisons illustrate consumer workflows;
they do not make every other terminal's setting a KetraTerm requirement.

The [gap map](../terminal-feature-gap-map.md#public-api-ergonomics-review) owns
current status and validation. This report preserves the review evidence and
acceptance criteria. P3 describes bounded customization friction with a
workaround, rather than a reproduced runtime defect.

| Finding | Priority | Consumer impact |
| --- | --- | --- |
| [API08](#api08-condensed-integer-column-spacing) | P3 | Condense cells without changing font size or terminal character width. |
| [API09](#api09-host-controlled-middle-button-paste) | P3 | Handle middle-button paste through host policy and deferred clipboard access. |
| [API10](#api10-independent-alternate-screen-wheel-input-control) | P3 | Disable wheel-to-arrow input independently of application mouse reporting. |

## API08 Condensed integer column spacing

**Public evidence.** At the reviewed revision,
[`SwingSettings.columnSpacing`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/settings/SwingSettings.kt)
accepts only nonnegative added integer pixels. Reducing the font size changes
vertical metrics and text size, rather than independently reducing cell advance.

**Consumer workflow.** An embedding host offers slightly denser horizontal
spacing for the existing font. A smaller advance also fits more columns into the
same component width. Changing core character-width policy is unrelated to this
presentation choice.

**Smallest direction.** Extend the existing integer setting to signed values.
Resolve font metrics before requiring a positive, integer-safe final cell width.
Preserve the original glyph-fitting budget when cells shrink, including complex
text and native emoji. Keep painting and interaction on the same adjusted cell
geometry. Do not add another font or geometry configuration model for this slice.

**Acceptance criteria.** Cover signed immutable construction/copy, defaults,
positive-spacing compatibility, one-pixel cells, invalid widths and overflow,
atomic rejected reloads, live resizing, retained closed-session presentation,
bidi/wide/cluster text, selection, hit testing, terminal mouse coordinates, and
fractional device scales. Check Java/Kotlin consumers and unchanged public ABI.
Keep the ASCII fast path and warmed cache behavior allocation-conscious.

**Comparison and boundary.**
[WezTerm's `cell_width`](https://wezterm.org/config/lua/config/cell_width.html)
supports condensation without rescaling glyphs. Its fractional scale is broader
than this accepted signed-integer slice. Exact fractional spacing remains a
separate geometry decision; completion of API08 does not claim continuous cell
advances or exact mapping of another renderer's percentage settings.

## API09 Host-controlled middle-button paste

**Public evidence.**
[`middleClickPaste`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/settings/SwingSettings.kt)
selects the built-in local gesture. Its clipboard callbacks run on the EDT.
[`TerminalClipboardHandler`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/settings/TerminalClipboardHandler.kt)
already distinguishes `readPrimarySelectionText()` from `readText()` and warns
that native reads may block. This is not a missing PRIMARY read API.
[`SwingHostServices`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingHostServices.kt)
has key and context-menu hooks but no local paste-gesture hook.
[`SwingTerminal.pasteText`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/api/SwingTerminal.kt)
lets hosts submit text after their own asynchronous read and identity check.

**Consumer workflow.** A host supplies platform PRIMARY selection, consent, or
deferred clipboard reads while retaining terminal-owned middle-button routing.
The host needs the gesture and intended source before obtaining the text. A
synchronous clipboard adapter cannot defer completion through its return value.

**Friction and workaround.** Disable built-in middle-button paste, intercept the
mouse gesture in a wrapper, read asynchronously, then call `pasteText` on the
EDT after checking the intended binding. This duplicates the local/application
route and Shift policy.

**Smallest direction.** Supply a narrow local middle-button paste request hook
with source identity and safe deferred completion. Preserve the built-in fallback
when no hook is supplied. Clipboard access, consent, and scheduling remain
host-owned; terminal routing and session paste admission remain library-owned.

**Acceptance criteria.** Notify once only for an enabled local middle-button
gesture; preserve application mouse tracking and Shift routing. Let hosts decline
or complete later without a synchronous native read. Reject completion for a replaced or
closed binding and a disposed view, including unbind/rebind of the same session.
Preserve paste policy, bracketed-paste protection, ordering, and actual admission
results. Define EDT ownership, callback failures, and repeated completion.

**Comparison.**
[VTE's `paste_primary`](https://gnome.pages.gitlab.gnome.org/vte/gtk3/method.Terminal.paste_primary.html)
distinguishes PRIMARY paste and associates it with the middle button. This
supports retaining source identity; it does not by itself prescribe a Kotlin
asynchronous API.

## API10 Independent alternate-screen wheel input control

**Public evidence.** The public
[`mouseReportingEnabled`](../../ketraterm-ui-swing/src/main/kotlin/io/github/ketraterm/ui/swing/settings/SwingSettings.kt)
contract explicitly leaves alternate-buffer wheel-to-arrow input independent.
The [published Swing ABI](../../ketraterm-ui-swing/api/ketraterm-ui-swing.api)
offers neither a separate enable switch nor a wheel policy hook.

**Consumer workflow.** A host wants wheel gestures to remain local while a TUI
uses the alternate screen. Disabling application mouse reporting does not also
disable synthesized arrow input.

**Friction and workaround.** Consume wheel events in host composition or avoid
the stock input listener. Both are broader than choosing this one fallback.

**Smallest direction.** Add an independent setting for alternate-screen
wheel-to-arrow fallback, preserving the current default. A general mouse binding
registry or a replacement input pipeline is unnecessary.

**Acceptance criteria.** Disabled fallback sends no arrow input. Preserve enabled
precise-wheel accumulation, active application mouse reporting, Shift-local
behavior, primary history scrolling, and existing route/binding reset behavior.
Clear residual wheel input when switching policy so a later event cannot emit
earlier disabled motion. Cover live settings reload, closure, and Java/Kotlin
construction.

## Considered without implementation TODOs

- **Inactive cursor presentation.** KetraTerm's public component contract fixes
  the unfocused presentation. A host requiring a hidden or independently styled
  inactive cursor would need a narrow presentation choice.
  [xterm.js exposes `cursorInactiveStyle`](https://xtermjs.org/docs/api/terminal/interfaces/iterminaloptions/#optional-cursorinactivestyle).
  This is a conditional appearance requirement, not a defect in the default.
- **Independent primary font faces.** The public settings select one primary
  font; `TerminalFontResolver` controls fallback rather than replacing each
  primary style face. A host requiring a distinct italic family has a concrete
  potential need, illustrated by
  [WezTerm's `font_rules`](https://wezterm.org/config/lua/config/font_rules.html).
  Retain the candidate until a host requirement establishes the required slice.
- **Word selection and consolidated read-only control.** These need a concrete
  consumer policy before adding library APIs. Existing programmatic selection,
  host key handling, and transport composition already cover several workflows.
  Do not infer support for multiple independently controlling views of one
  session from these candidates.
