# Terminal Completion Persistence Agent Guide

`ketraterm-completion-persistence` owns bounded local storage for exact-command
learning snapshots shared by product hosts. Read the root [AGENTS.md](../AGENTS.md)
and [Module.md](Module.md) before changes.

## Ownership

- Completion owns learning models, ranking, replay policy, and the host filter.
  Recheck replay eligibility and matching successful evidence at the file boundary.
- This module owns versioned encoding, bounded file access, temporary-file
  replacement, and checkpoint coordination.
- Products choose the fixed path, enablement, diagnostics, coroutine scope, and
  shutdown budget. Keep UI, PTY, session, workspace, and product dependencies out
  of this module.

## Invariants

- Learning mutations become visible before recording returns, without waiting
  for hydration or disk. Coordinator mutations must update revision tracking.
- Use one worker in the caller-supplied scope and one conflated wakeup. Keep file
  I/O off the state lock; do not enqueue per-event snapshots.
- Hydrate the fixed file once and merge distinct aggregate evidence once.
  Preserve live mutations during hydration and file writes.
- Reject incompatible or unreadable snapshots without ordinary overwrite, and
  report the load failure once. Reset is the explicit replacement path and must
  supersede hydration and write empty state even when persistence is disabled.
- Preserve bounds, strict decoding, replay sanitization, and same-directory
  temporary-file cleanup. Keep the codec and file store internal.
- Flushing close rejects subsequent mutations and awaits the final required
  write. Cancellation must propagate; the host owns a bounded durability wait
  and cancellation of its scope when that budget expires.

## Validation

Run `./gradlew spotlessApply` and
`./gradlew :ketraterm-completion-persistence:test` for behavior changes. Use virtual
time for checkpoint scheduling and explicit synchronization for in-flight I/O
tests. Cover malformed input, hard bounds, hydration and reset races, failed
writes, and both close modes. Update [README.md](README.md) when product integration
contracts change; documentation-only validation follows the root guide.
