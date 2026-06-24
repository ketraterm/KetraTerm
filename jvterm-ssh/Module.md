# jvterm-ssh

`jvterm-ssh` provides SSH-backed terminal transport connectors for JvTerm.

The module adapts an authenticated SSH PTY shell channel to the transport-neutral
`TerminalConnector` contract. Parser, core, input encoding, rendering, and UI
behavior remain owned by their existing modules.
