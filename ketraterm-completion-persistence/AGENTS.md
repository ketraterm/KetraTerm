# Terminal Completion Persistence Agent Guide

`ketraterm-completion-persistence` owns bounded local filesystem persistence for completion-learning snapshots.

## Responsibility

This module may:

- encode and decode completion snapshots through public completion contracts.
- sanitize snapshots at the storage boundary.
- perform versioned, atomic local-file replacement.
- serialize and coalesce background writes.

## Boundary

This module must not:

- parse command lines, rank candidates, or compose completion sources.
- depend on Swing, IntelliJ Platform, session, workspace, PTY, or app modules.
- choose product-specific storage directories or persistence settings.
- expose its worker queue as public API.

Product hosts choose the destination path and own enablement, lifecycle, and user-facing settings. The dependency-free
completion engine remains free of filesystem and scheduling concerns.
