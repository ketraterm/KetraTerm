# Terminal Workspace Agent Guide

`terminal-workspace` owns host-neutral terminal workspace state for products
that present one or more local terminal sessions.

## Responsibility

This module may:

- define terminal launch profiles and profile discovery policy.
- track open terminal tabs and selected tab identity.
- coordinate local PTY-backed session creation through `terminal-pty`.
- propagate host-neutral tab events such as title changes, bell, failures, and
  close notifications.

## Boundary

This module must not:

- import Swing, AWT, IntelliJ Platform APIs, or other UI toolkits.
- parse terminal protocols.
- mutate terminal core internals.
- encode keyboard, paste, focus, or mouse bytes directly.
- own PTY stream threads or process implementation details.

PTY process lifecycle stays in `terminal-pty`; session synchronization stays in
`terminal-session`; UI modules adapt workspace state to visual containers.
