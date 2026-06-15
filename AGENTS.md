# Terminal Pipeline Agent Guide

This repository is building a modern, secure terminal pipeline in Kotlin/JVM 21.
It is not chasing literal full xterm parity. The goal is a clean, fast,
professional terminal architecture for contemporary shells and TUIs.

Read this file before making changes. Then read the module-level `AGENTS.md`
for the module you touch.

## Architecture

The project is split into strict layers:

- `jvterm-protocol`: shared protocol constants and small vocabulary types with
  no dependency on parser, core, host, or input.
- `jvterm-parser`: byte stream to semantic terminal commands.
- `jvterm-core`: headless terminal state, grid physics, modes, attributes,
  scrollback, width policy, and storage.
- `jvterm-host`: adapters that map parser semantic commands to core
  APIs.
- `jvterm-input`: host-bound input encoding for keyboard, paste, focus, and
  future mouse reports.
- `jvterm-render-api`: dependency-free primitive render frame, cursor, cell,
  cluster, and attribute vocabulary.
- `jvterm-render-cache`: renderer-side cache that copies primitive render
  frames for UI consumers.
- `jvterm-transport-api`: dependency-free connector contract for byte-stream
  transports.
- `jvterm-session`: runtime synchronization point that connects transport,
  parser, host, core response queues, and input encoding.
- `jvterm-ui-swing`: reusable Swing terminal UI component, painting,
  selection, input event handling, clipboard/font/settings abstractions, and
  viewport/scrollbar model.
- `jvterm-ui-swing-demo`: standalone Swing demo host that wires the reusable
  UI component to a local PTY-backed session for manual testing.
- `jvterm-testkit`: reusable public test fakes for connector/session tests.
- `jvterm-pty`: local PTY process lifecycle exposed as transport connectors.
- `jvterm-benchmarks`: JMH benchmarks for performance-sensitive terminal
  paths.

Keep these boundaries intact:

- Protocol owns shared terminal vocabulary such as ANSI/DEC mode ids and mouse
  mode enums. It must stay dependency-free.
- Parser parses. It owns UTF-8 decoding, ANSI state machines, CSI/OSC/DCS
  recognition, charset shifts, grapheme segmentation, and semantic dispatch.
- Core mutates and stores. It owns cursor physics, margins, wrapping, tab stops,
  scrollback, pen attributes, width calculation, and mode state.
- Integration maps. It must not parse protocols and must not reach into core
  internals.
- Input encodes. It reads stable input-facing mode state and writes host-bound
  bytes without parsing terminal output or touching grid/cursor internals.
- Render API exposes primitive frame contracts only. It must not depend on UI,
  Swing, PTY, parser, host, or core internals.
- Render cache copies render frame data for consumers. It must not choose host
  fonts, parse terminal bytes, or own Swing painting policy.
- Transport connects. Connectors own host I/O threads, deliver raw bytes in
  stream order, synchronously consume host-bound write ranges, and never parse
  terminal protocols.
- Session serializes. It owns parser/core mutation synchronization, drains core
  response bytes, and serializes UI input plus core responses through one
  outbound write lock.
- Swing UI displays and interacts. It must not import IntelliJ APIs, contain
  PTY-specific code, parse terminal output, or know whether bytes come from PTY,
  SSH, tests, or another transport.
  It may use `jvterm-input` event vocabulary but must send encoded intent
  through `TerminalSession` rather than writing host bytes directly.
- Swing demo hosts. It may start PTY sessions and create windows, but reusable
  rendering and input behavior still belong in `jvterm-ui-swing`.
- PTY hosts. It starts local pseudo-terminal processes and exposes them through
  `TerminalConnector`. It must not parse protocols, encode input itself, or
  mutate core internals.

Width calculation belongs in core. The parser may assemble grapheme clusters,
but it must not decide how many grid cells a cluster occupies because width
depends on terminal mode and policy.

## Non-Goals

Do not add these unless the product direction explicitly changes:

- Tektronix 4014 emulation.
- Media Copy / printer passthrough.
- X11-specific font protocols.
- Blind OSC 52 clipboard writes.
- Unbounded or unaudited OSC/DCS query responses.
- "Everything xterm ever accepted" compatibility.

Use `docs/terminal-feature-map.md` as the living source for supported features,
and `docs/terminal-feature-gap-map.md` for missing, intentionally deferred,
and policy-gated features.

## Engineering Rules

- Preserve strong SRP. A feature belongs in exactly one responsible layer.
- When implementing or extending query/response features (such as `DECRQSS` or `XTGETTCAP`), or when creating new terminal features that can be queried, always update the explicit security allowlist of queried settings or capabilities, and reject unauthorized or unsupported queries with standard protocol-defined failure responses.
- Prefer the existing module structure and local helper APIs over new patterns.
- Keep hot paths allocation-free or allocation-minimal: primitive arrays,
  packed integers, generated-table-shaped lookups, and explicit buffers.
- Do not use regex, ICU, `BreakIterator`, or object-heavy parsing in parser/core
  hot paths.
- Do not fake unsupported behavior. Add a `TODO(parser-gap)`,
  `TODO(core-gap)`, `TODO(host)`, or `TODO(policy)` and document it in
  the gap map.
- Keep behavior table-driven where appropriate, especially CSI/SGR/Unicode
  classification.
- Avoid broad refactors while adding protocol behavior. Tight changes are
  easier to verify and safer for terminal semantics.
- Keep comments and KDoc current. All public classes, interfaces, methods,
  properties, enum values, and public data models should have useful KDoc that
  explains the contract, parameters, return values, ownership, and important
  terminal semantics. Internal/private comments are welcome only when they
  clarify non-obvious invariants, hot-path tradeoffs, protocol rules, or safety
  constraints.
- Remove stale, misleading, deprecated, or compatibility-only comments and APIs
  instead of preserving legacy wording. This is a new product, so do not carry
  deprecated surfaces or old behavior notes unless the product explicitly needs
  a migration path.
- Avoid noise comments that merely restate the code. Prefer no comment over a
  comment that can become wrong without adding meaning.

## Testing Doctrine

Tests must assert real terminal semantics, not current implementation quirks.
If the implementation is wrong, the test should fail.

For every behavior change:

- Add or update focused unit tests for the responsible component.
- Add host tests for real byte streams when parser behavior is involved.
- Cover normal cases, omitted/default parameters, malformed input, overflow,
  boundary values, recovery behavior, and chunking where relevant.
- Use recording fixtures to keep assertions explicit, but do not hide the
  semantic expectation inside helpers.
- Do not loosen assertions to make broken behavior pass.
- For new protocol files, write tests first where feasible.

## Definition of Done

A change is not done until:

- It is implemented in the correct layer.
- Relevant parser/core/host tests exist and pass.
- Edge and hostile cases are covered, not only the happy path.
- Unsupported parts are explicit TODOs, not silent no-ops pretending to work.
- `docs/terminal-feature-map.md` and `docs/terminal-feature-gap-map.md` are
  updated when capability or scope changes.
- `./gradlew spotlessApply` has been run after edits and before final
  verification.
- No unrelated formatting churn or architecture drift is introduced.

## Useful Commands

- Full test suite: `./gradlew test`
- Format Kotlin and Gradle files: `./gradlew spotlessApply`
- Parser tests: `./gradlew :jvterm-parser:test`
- Core tests: `./gradlew :jvterm-core:test`
- Integration tests: `./gradlew :jvterm-host:test`
- Session tests: `./gradlew :jvterm-session:test`
- Render cache tests: `./gradlew :jvterm-render-cache:test`
- Swing UI tests: `./gradlew :jvterm-ui-swing:test`
- Swing UI demo: `./gradlew :jvterm-ui-swing-demo:run`
  - Custom shell: `./gradlew :jvterm-ui-swing-demo:run --args="cmd.exe"`
- PTY tests: `./gradlew :jvterm-pty:test`
- Benchmarks: `./gradlew :jvterm-benchmarks:jmh`

In sandboxed sessions, Gradle may need approval because wrapper/cache writes can
leave the workspace.

## Start Here

- Feature directory: `docs/terminal-feature-map.md`
- Feature backlog: `docs/terminal-feature-gap-map.md`
- Core contract: `jvterm-core/docs/terminal-core-contract.md`
- Project skills index: `docs/agent-skills.md`
- Repo-local Codex skills: `.agents/skills/*/SKILL.md`
