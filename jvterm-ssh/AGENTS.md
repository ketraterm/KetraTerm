# Terminal SSH Agent Guide

`jvterm-ssh` owns SSH client transport lifecycle and exposes remote PTY shell
channels through `jvterm-transport-api`.

## Boundary

SSH owns:

- SSH TCP connection, authentication, host-key verification, and shell channel
  lifecycle.
- Pumping remote shell bytes to `TerminalConnectorListener`.
- Writing host-bound byte ranges to the SSH channel input stream.
- Sending SSH PTY resize/window-change requests.

SSH must not:

- parse terminal escape sequences.
- mutate core grid/cursor state directly.
- encode keyboard, paste, focus, or mouse bytes itself.
- weaken host-key verification defaults.
- log passwords, private key material, or raw terminal byte streams.

## Testing

Unit tests should use fake shell clients for connector lifecycle and byte
ordering. Embedded or real SSH server tests should be opt-in unless they are
fully deterministic and fast.
