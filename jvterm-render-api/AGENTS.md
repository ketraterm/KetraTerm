# Terminal Render API Agent Guide

`terminal-render-api` defines the dependency-free public render contract shared
by core, session, render cache, and UI modules.

## Boundary

Render API owns stable primitive frame, cursor, cell flag, cluster, and render
attribute vocabulary.

Render API must not:

- depend on core, parser, integration, session, PTY, Swing, Compose, AWT, Skia,
  or any host module.
- expose core internal storage, cluster handles, cell objects, or mutable
  terminal state.
- define renderer-specific glyph runs, paint caches, font choices, or UI timing.

Keep this module small, stable, and allocation-conscious. Consumers receive
short-lived frame views and copy primitive row data into their own caches.
