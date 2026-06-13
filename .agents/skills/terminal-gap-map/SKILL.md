---
name: terminal-gap-map
description: Terminal feature gap-map maintenance guidance. Use when documenting missing parser/core/host/input/policy/host support, closing TODOs, classifying modern terminal features, or deciding whether a protocol belongs in scope.
---

# Terminal Gap Map

Use this skill when updating `docs/terminal-feature-gap-map.md` or deciding how
to classify terminal support.

## Rules

- Keep gaps explicit. Missing support should be documented, not hidden behind
  silent no-ops.
- Mark ownership with `TODO(parser)`, `TODO(core)`, `TODO(host)`,
  `TODO(input)`, `TODO(host)`, or `TODO(policy)`.
- Move items to `DONE(...)` only when the claimed scope has implementation and
  tests.
- Keep non-goals visible so future agents do not build obsolete or risky legacy
  surface by accident.

## Target

The target is a modern, secure, state-of-the-art terminal pipeline, not literal
full xterm.

Prioritize:

- UTF-8, graphemes, and core-owned width.
- CSI cursor/edit/erase/scroll/modes.
- SGR 16/256/RGB and common attributes.
- OSC title and hyperlinks.
- bracketed paste and SGR mouse.
- alternate screen.
- terminal input encoding.
- generated Unicode tables.

Policy-gate risky response/host-affecting features such as OSC 52 clipboard,
DCS query responses, window manipulation, and desktop notifications.
