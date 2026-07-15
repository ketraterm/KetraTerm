# Coroutine render publication

`TerminalSession` owns one long-lived render worker coroutine in a `SupervisorJob`. The dispatcher is configurable and non-owned; closing a session cancels its jobs, not the dispatcher.

## Invalidation and conflation

A conflated `Channel<Unit>` wakes the worker. Viewport offset and requested row count stay packed in one atomic `Long`, while an atomic generation identifies newer invalidations without allocating request objects.

The worker always extracts the latest requested viewport under `mutationLock`, copies it into `TerminalRenderPublisher`, and updates `renderGeneration: StateFlow<Long>` only after promotion succeeds. If requests arrive during a copy, the worker performs at most the additional copies needed to reach the latest generation.

New host output preserves the current requested scrollback offset. A UI viewport change replaces that request. One session therefore supports one active render viewport; independently scrolling views require separate sessions.

## Failure and synchronized output

Copy failure keeps the previous published cache and does not spin. That generation is retried only after a newer invalidation. Coroutine cancellation is always rethrown.

Synchronized-output mode defers publication. A cancellable child job disables the mode after the safety timeout and invalidates rendering. Disabling the mode normally cancels the timeout and publishes immediately.

## Allocation boundary

Invalidation allocates no render-request object. Parser/core mutation, per-cell copying, codepoint handling, and ordinary input encoding contain no coroutine dispatch. Core response bytes continue to use the preallocated session response buffer.
