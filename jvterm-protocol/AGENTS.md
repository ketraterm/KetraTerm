# Terminal Protocol Agent Guide

`terminal-protocol` holds dependency-free terminal protocol vocabulary shared by
parser, core, integration, input, and host-facing modules.

## Boundary

Protocol owns small, stable identifiers and enums such as control-code byte
values, ANSI/DEC mode ids, and mouse reporting vocabulary.

Protocol must not:

- parse bytes or escape sequences.
- mutate terminal state.
- encode keyboard, mouse, focus, or paste reports.
- depend on parser, core, integration, rendering, or host modules.

Keep additions narrow and semantic. If a feature needs behavior, implement that
behavior in the owning layer and expose only the shared vocabulary here.
