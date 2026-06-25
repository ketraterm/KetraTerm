# Terminal Render Cache Agent Guide

`jvterm-render-cache` owns renderer-side copies of primitive render frames. It
lets UI consumers work from stable cached row, cluster, attribute, hyperlink,
wrap, cursor, and generation data without reaching back into core storage.

## Boundary

Render cache owns:

- copying `TerminalRenderFrameReader` data from `jvterm-render-api`.
- retaining copied structure and line generations for renderer-side comparison.
- retaining copied cluster text and flattened primitive cell planes.
- publishing cache snapshots with clear ownership between render workers and UI
  readers.

Render cache must not:

- parse terminal output protocols.
- mutate core, session, transport, or UI state.
- choose fonts, colors, glyph runs, paint strategy, or Swing repaint policy.
- depend on parser, core, host, session, PTY, Swing, or host modules.

Keep cache updates allocation-conscious. Reuse primitive cell-plane storage and
clear cluster row ranges deliberately when copied frame data changes.

## Testing

Tests should assert copy semantics, resize behavior, cluster clearing, cursor
updates, and publisher ownership rules. Use fake
`TerminalRenderFrameReader` instances rather than a live terminal session.
