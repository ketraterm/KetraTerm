# Terminal Render Cache Agent Guide

`terminal-render-cache` owns renderer-side copies of primitive render frames. It
lets UI consumers work from stable cached row, cluster, attribute, hyperlink,
wrap, cursor, and dirty-row data without reaching back into core storage.

## Boundary

Render cache owns:

- copying `TerminalRenderFrameReader` data from `terminal-render-api`.
- tracking row dirtiness from structure and line generations.
- retaining copied cluster text and primitive row arrays.
- publishing cache snapshots with clear ownership between render workers and UI
  readers.

Render cache must not:

- parse terminal output protocols.
- mutate core, session, transport, or UI state.
- choose fonts, colors, glyph runs, paint strategy, or Swing repaint policy.
- depend on parser, core, integration, session, PTY, Swing, or host modules.

Keep cache updates allocation-conscious. Reuse primitive row storage and clear
cluster rows deliberately when copied frame data changes.

## Testing

Tests should assert copy semantics, dirty-row behavior, resize behavior, cluster
clearing, cursor updates, and publisher ownership rules. Use fake
`TerminalRenderFrameReader` instances rather than a live terminal session.
