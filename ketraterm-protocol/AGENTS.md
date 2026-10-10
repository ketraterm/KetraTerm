# Terminal Protocol Agent Guide

`ketraterm-protocol` owns shared wire identifiers, semantic values, capability
vocabulary, and the synchronous host-output contract. Read the root
[agent guide](../AGENTS.md) before changing this module.

## Boundary

Keep this module independent of other KetraTerm modules and third-party runtime
libraries. Parsing, grid mutation, input encoding, transport lifecycle, host
policy, and UI actions belong to their owning layers. Small validation helpers
may enforce vocabulary invariants without introducing those behaviors.

## Representation invariants

- Preserve protocol-defined numeric values. Do not substitute normalized state
  values for wire parameters or capability bits.
- Keep root-package mouse enum order aligned with the normalized constants in
  `protocol.mouse` and core's packed input-state decoder.
- Capability identifiers describe available behavior, not authorization. A host
  must advertise only metadata or actions it can supply.
- `TerminalHostOutput` calls synchronously consume or copy borrowed array ranges.
  Producer ordering and sink lifecycle remain the caller's responsibility.
- Keep validated clipboard selectors distinct; do not collapse primary,
  secondary, or cut-buffer selectors into the host clipboard.

## Validation

For vocabulary changes, add focused value/validation tests here and verify the
owning parser, core, host, or input behavior where the values are consumed. Run
`./gradlew spotlessApply`, then `./gradlew :ketraterm-protocol:test` and the
relevant consumer tests. Protocol documentation must distinguish an identifier's
meaning from current support and policy.
