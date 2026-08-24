# Terminal Completion Persistence Agent Guide

`ketraterm-completion-persistence` owns bounded local filesystem persistence for exact-command learning snapshots.

## Responsibility

This module may:

- encode and decode exact-command snapshots through public completion contracts.
- sanitize snapshots at the storage boundary.
- perform versioned, atomic local-file replacement.
- apply bounded in-memory learning synchronously in one lifecycle coordinator.
- debounce and conflate write generations through one latest-snapshot writer.
- expose passive internal repository and bounded file-store I/O.
- expose a suspending flush barrier for host lifecycle coordination.

## Boundary

This module must not:

- parse command lines, rank candidates, or compose completion sources.
- depend on Swing, IntelliJ Platform, session, workspace, PTY, or app modules.
- choose product-specific storage directories or persistence settings.
- create or implicitly own coroutine scopes.
- persist any learning family beyond exact-command aggregates.

The persistence coordinator may create one bounded control worker and one
latest-snapshot writer as children of its caller-supplied scope; it must not
create an executor or independent scope. Learning must become visible before a
recording call returns and must not wait for hydration or disk. The control
worker handles only hydration, enablement, and flush barriers. The writer must
debounce and conflate bursts, materialize only the latest immutable snapshot,
and keep flush semantics deterministic.

Product hosts choose one fixed destination at coordinator construction, supply
the scope, and own enablement, lifecycle, and user-facing settings. Do not add
runtime path switching or snapshot-import semantics. The internal repository is
a passive I/O boundary and must not acquire a second mutex, queue, cache, or
scope. The dependency-free completion engine remains free of filesystem and
scheduling concerns.
