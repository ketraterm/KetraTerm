# Terminal Completion Persistence Agent Guide

`ketraterm-completion-persistence` owns bounded local filesystem persistence for completion-learning snapshots.

## Responsibility

This module may:

- encode and decode completion snapshots through public completion contracts.
- sanitize snapshots at the storage boundary.
- perform versioned, atomic local-file replacement.
- serialize learning and writes with one suspending repository and mutex.
- adapt repository operations to one ordered worker in an explicitly caller-owned lifecycle scope.
- expose a suspending flush barrier for host lifecycle coordination.

## Boundary

This module must not:

- parse command lines, rank candidates, or compose completion sources.
- depend on Swing, IntelliJ Platform, session, workspace, PTY, or app modules.
- choose product-specific storage directories or persistence settings.
- create or implicitly own coroutine scopes.

The persistence coordinator may create exactly one ordered command queue and
worker as children of its caller-supplied scope; it must not create an executor
or independent scope. Product hosts choose the destination path, supply the
coordinator scope, and own enablement, lifecycle, and user-facing settings. The dependency-free
completion engine remains free of filesystem and scheduling concerns.
