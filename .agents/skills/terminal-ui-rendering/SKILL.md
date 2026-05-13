---
name: terminal-ui-rendering
description: Use when implementing or reviewing terminal UI/rendering work such as Swing painting, render-frame consumption, text runs, fonts, colors, cursor rendering, selection, viewport/scrollback behavior, standalone host wiring, or IDE host integration.
---

# Terminal UI Rendering

Use this skill as a workflow for UI and rendering work. Module ownership rules
belong in root `AGENTS.md` and the relevant module-level `AGENTS.md`; read those
before editing.

## 1. Classify Ownership

Route work to the narrowest owning layer:

- reusable Swing component behavior: `terminal-ui-swing`
- Java2D painting, cursor, selection, viewport: `terminal-ui-swing`
- Swing settings, font, color, and clipboard abstractions: `terminal-ui-swing`
- render-frame contract changes: `terminal-render-api`
- render-frame caching and publishing changes: `terminal-render-cache`
- host window/app/IDE wiring: host modules outside reusable Swing UI

Do not put host-specific code into reusable UI code.

## 2. Enforce Swing UI Boundaries

For `terminal-ui-swing` changes, verify:

- no IntelliJ Platform imports.
- no dependency on `terminal-pty`.
- no PTY, SSH, WebSocket, or transport-specific logic.
- no ANSI/VT/OSC/DCS parsing.
- no direct terminal core mutation.
- no duplicated render-cache responsibility.
- no process/session creation policy.
- no IDE or standalone lifecycle policy.

The UI consumes render-frame state and sends user intent through session/input
boundaries.

## 3. Keep Components Host-Owned From the Outside

The public Swing terminal component should own its internal layer assembly.
Hosts configure it through small interfaces for settings, fonts, clipboard,
colors, and lifecycle hooks. Host modules should not manually assemble internal
painting, cursor, or selection pieces.

## 4. Protect Rendering Hot Paths

Rendering should be run-based, not cell-object based.

Prefer renderer logic organized around:

- background runs.
- text runs.
- decoration runs.
- selection ranges.
- cursor presentation.
- complex cluster fallback.

Reject hot-path designs that allocate per cell, resolve fonts per cell, create
`Color` or `String` objects per cell, or call `drawString` once per cell. Use
primitive packed values where practical, especially packed ARGB colors.

## 5. Split Simple and Complex Text

The ASCII/simple-text path must stay fast:

```text
ASCII/simple text -> fast run builder -> cached font -> grouped draw
Unicode fallback/clusters -> isolated slow path -> TextLayout or equivalent
```

Font fallback must be cached and resolved by runs where possible. Complex
clusters may use slower APIs, but they must not force the whole renderer onto
the slow path.

## 6. Snapshot Rendering State

Painting and off-EDT rendering must consume immutable state:

- terminal fonts.
- cell metrics.
- resolved palette.
- text rendering hints.
- HiDPI scale.
- cursor settings.
- selection colors.

Do not read live theme, font, scale, metric, or palette state while painting. A
renderer must not observe half-applied theme changes.

## 7. Validate Metrics and Colors

Cell metrics must be explicit and frozen together:

- cell width.
- cell height.
- baseline.
- underline, strikethrough, and overline positions.
- cursor stroke width.

Color resolution should define default foreground/background, ANSI 16, indexed
256-color, truecolor RGB, bold-as-bright, faint/dim, inverse, conceal,
selection, and cursor colors.

When adding dynamic palette support, route palette changes through the same
snapshot/publish mechanism as theme changes.

## 8. Respect Swing Threading

Swing component state belongs to the EDT.

Off-EDT rendering is allowed only when it:

- publishes immutable frame or strip snapshots.
- never mutates images or buffers currently visible to the EDT.
- uses double buffering or equivalent ownership transfer.
- avoids touching Swing component state off the EDT.
- snapshots settings before worker rendering starts.

## 9. Keep Host Integration Thin

Host integrations may provide window assembly, config/settings providers,
clipboard adapters, font/theme adapters, PTY or remote session startup, actions,
search/chrome, and lifecycle/disposal hooks.

Those concepts must enter reusable Swing UI through small interfaces. Host
modules should not fork or duplicate Swing renderer logic.

## 10. Review Input and Selection

Keyboard handling must send terminal intent through the session/input boundary.

Mouse handling must keep selection state UI-local unless terminal mouse protocol
events are intentionally encoded and sent to the session.

Selection should avoid allocations during drag and paint by row ranges where
possible. Cursor blinking should not repaint terminal content unless content
actually changed.

## 11. Test Deterministically

Prefer render-frame replay and model tests before live PTY or IDE tests.

Useful tests:

- render-frame replay without a live PTY.
- dirty-row repaint behavior.
- viewport offset math.
- selection range math.
- keyboard translation through the session/input boundary.
- mouse selection behavior.
- resize-to-columns/rows calculation.
- color resolution.
- inverse/faint/conceal/selection interactions.
- cursor shape and repaint bounds.
- settings snapshot rebuild behavior.
- render-cache consumption.

Avoid requiring a real PTY or IDE runtime for reusable Swing UI tests.

## 12. Final Review Checklist

Before accepting a UI/rendering change, verify:

- module ownership is correct.
- no forbidden dependencies were added.
- rendering remains run-based.
- ASCII/simple-text path remains fast.
- complex text fallback is isolated.
- no per-cell allocation was introduced.
- settings, metrics, and palette are snapshotted.
- Swing state stays on the EDT.
- off-EDT buffers, if any, have clear ownership.
- host integration remains adapter-only.
- deterministic tests cover the behavior.
