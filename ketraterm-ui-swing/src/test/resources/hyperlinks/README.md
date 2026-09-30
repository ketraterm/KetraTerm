# agy authentication replay

`agy-auth-176x32.ansi` is a sanitized, byte-preserving capture of the installed
Antigravity CLI on Windows ConPTY, taken on 2026-09-30. The capture starts at
process startup and ends when the OAuth authorization-code prompt is visible.
It is **7,502 bytes**, UTF-8 with terminal control sequences; do not normalize
line endings, format it, or substitute a generated approximation.

- Initial and final terminal geometry: **176 columns × 32 rows**. No resize.
- Fresh isolated `ANTIGRAVITY_APP_DATA_DIR` and `XDG_CONFIG_HOME`; no existing
  account data, shell startup output, or authorization code was used.
- `TERM=xterm-256color`, `COLORTERM=truecolor`, no `WT_SESSION` or `TERM_PROGRAM`,
  automatic CLI updates disabled. Select Google OAuth with Enter from the
  signed-out login chooser. Stop before signing in.
- Captured the connector's incoming bytes before parsing. The fixture includes
  the startup redraw, cursor positioning, CR/LF, SGR, OSC 8 openings/closings,
  original padding, and the authentication prompt.
- SHA-256: `4B605557E23DD07CC514E0760852F0DFD7DAD5583A4518C92267CF40C46E9333`.

Sanitization replaces the 19-character authorization hostname with
`www.example.invalid`. Query keys and separators are preserved; query-value
letters become `x`, digits become `0`, and percent escapes become `%78`.
The same replacements are applied at matching offsets in the visible URL
fragments. The explicit 10-character OSC 8 ID becomes `agyfixture`. Every
replacement has the original byte length; control bytes and non-URL text are
unchanged. The private capture and isolated application data were removed
after sanitization.

All six OSC 8 openings contain that same ID and the same 704-character target.
The URL occupies five separately emitted fragments of 174, 174, 174, 174, and
8 characters. The sixth segment is the separate authentication label.
The capture demonstrates application-authored line layout, not a single URL
left to terminal soft wrapping. Replay assertions establish its actual grid
geometry and destination identity; hover grouping observations are diagnostic
until the grouping implementation is corrected.

This is a fresh reproduction of the screen reported by the user, not a recovery
of the bytes behind their screenshot. The screenshot alone does not establish
its original terminal geometry or CLI version. The captured executable did not
expose a Windows file/product version.
