---
name: terminal-test-suite
description: Use when designing, reviewing, hardening, or refactoring terminal tests, or when adding asynchronous, concurrent, lifecycle, timing, or native/UI integration tests alongside a feature. Do not invoke for trivial synchronous assertion changes.
---

# Terminal Test Design

Read the root and owning module guide. This skill supplements their validation
rules for test-focused work.

## Review rules

- Assert public terminal semantics or a named internal invariant.
- Keep expected events, bytes, cells, and state transitions visible in the test.
- Use fixtures for setup and recording, not to hide the expectation.
- Prefer the narrowest deterministic layer that proves the contract.
- Add a full byte-stream or parser-to-core test only when the behavior crosses
  that boundary.
- Replace vague `doesNotThrow` coverage with observable outcomes.
- Do not preserve a known bug by weakening or restating assertions.
- Keep hostile, boundary, recovery, and lifecycle cases proportional to the
  contract under review.

Refactor repetition only when the resulting tests remain easier to understand
and failures still identify the broken semantic rule.

## Deterministic scheduling and time

- Never use sleep, a real delay, elapsed-time thresholds, or repeated wall-clock
  polling to establish correctness. A longer timeout or retry does not repair
  an ordering assumption. Advance an injected clock/scheduler or await the
  exact event that makes the assertion valid.
- For coroutine timing, use `runTest` and dispatchers sharing its
  `TestCoroutineScheduler`. Inject every dispatcher involved in the behavior,
  including nested I/O workers; `runTest` alone does not control hardcoded
  `Dispatchers.Default` or `Dispatchers.IO`. Use `runCurrent` for runnable work
  and explicit virtual-time advancement for deadlines. Avoid `advanceUntilIdle`
  on a live repeating poller; cancel owned work at test teardown.
- For real thread/EDT/I/O boundary tests, use explicit entered/release/completed
  handshakes. A bounded wait may fail a hung test, but its expiry must never be
  evidence that a callback cannot happen, a lock is held, or a task was cancelled.
  Establish that the contender reached the contested operation before asserting
  ordering. Propagate worker failures to the test thread and release every gate
  in `finally`; fake reads/processes must not silently produce EOF or exit when
  a wait expires.
- StateFlow is conflated state, not an event log. Assert state after controlled
  transitions; assert exact event order only when the contract guarantees it.
  Exercise rapid enable/disable, cancellation/restart, and completion after
  disposal explicitly instead of trusting one favorable scheduler interleaving.
- Keep UI state on the EDT. One `invokeAndWait` drains earlier queued work; it
  does not prove that a background producer, a timer, or a native window manager
  has finished. Test geometry, resize eligibility, and animation with controlled
  inputs; drive timer callbacks or model time explicitly. Keep real platform
  smoke tests separate from deterministic contract tests.
- Native PTY tests use a readiness/completion marker and input-controlled child
  lifetime when they need a live process. Await output before releasing the
  process; process exit alone need not mean its output was consumed. Assert
  terminal semantics, accounting for transport transformations such as ConPTY.
- Reuse existing clocks, dispatchers, and event hooks. Add a narrow seam at the
  owning boundary only when needed to control an actual dependency; do not
  expose private implementation state or add a framework of test-only adapters.

## Audit and verification

Run the owning tests with controlled alternate event orders. For cross-platform
failures, preserve the failing semantic assertion and remove the uncontrolled
dependency; do not skip the platform or weaken assertions to make CI green.
