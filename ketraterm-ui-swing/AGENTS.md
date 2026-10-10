# Terminal UI Swing Agent Guide

Read the [root guide](../AGENTS.md) first. This module owns the reusable Swing
component, rendering, and interaction. Consult [Module.md](Module.md) for source
navigation and the [README](README.md) for public contracts.

## Boundaries

- Consume session/render contracts and send input intent through the session.
  Do not parse output protocols, mutate core internals, or duplicate render-cache
  ownership.
- Keep PTY, SSH/WebSocket implementations, IntelliJ, standalone application,
  and product lifecycle policy outside this module.
- The public component owns its internal layers. Host integrations configure
  public services rather than assembling internal painters or controllers.
- Keep implementation types internal. Add a public seam only when a consumer
  needs it to express a real host responsibility.

## Rendering and geometry

- Use primitive frame/cache data and reusable run buffers. Preserve the ASCII
  path; isolate contextual shaping, `TextLayout`, and native emoji fallback.
- Avoid per-cell `Color`, `String`, `Font`, coordinate, or wrapper allocations.
  Use bounded caches and resolve host/theme data outside painting.
- Painting, cursor, selection, overlays, and hit testing must share the same
  bidi, wide-cell, padding, and fractional-scroll geometry.
- Calculate metrics when settings change, not while painting rows. Publish
  settings and metrics coherently; validate geometry before installation.
- Ordinary text tries host/configured fallback before enabled system scans.
  Cache font resolution and keep scanning out of the ASCII path.

## Threading and lifecycle

- Swing component state and its painters/controllers belong to the EDT.
  Respect the per-method public threading contract.
- Worker code may read captured source data and publish validated results; it
  must not access Swing state or mutate buffers visible to the EDT.
- Bindings own render observation, search/discovery, and suggestion requests.
  Reject obsolete publication after rebinding, invalidation, closure, or disposal.
- Host services and sessions remain host-owned. View cleanup must not close
  them. Keep source capture and host callbacks outside painting and mutation locks.

## Validation

Use deterministic model and frame-replay tests for geometry, damage, input
routing, settings reload, and lifecycle. Cover wide/cluster and bidi cells where
geometry changes. Do not require a real PTY or IntelliJ runtime. For asynchronous
tests, use controlled dispatchers or explicit handshakes rather than sleeps.

Run `./gradlew spotlessApply`, then `:ketraterm-ui-swing:test` and relevant
targeted checks. Public API changes require `checkKotlinAbi` and published
consumer verification; update an ABI baseline only after reviewing the change.
Internal helper benchmarks live in `src/jmh`; validate compilation with
`:ketraterm-benchmarks:jmhJar`. Allocation measurements belong in JMH, not exact
byte-count assertions in unit tests.

Capability status and deferred work belong in the root
[feature map](../docs/terminal-feature-map.md) and
[gap map](../docs/terminal-feature-gap-map.md). Do not copy those inventories or
their TODO taxonomy into this guide.
