# Terminal Completion Host Support Agent Guide

`ketraterm-completion-host` owns host-neutral asynchronous completion support shared by standalone and IDE products.

## Boundary

This module may:

- schedule bounded background snapshot work.
- publish immutable, generation-safe ready snapshots.
- resolve explicitly local filesystem completion paths.
- perform bounded local directory scans outside UI threads.

This module must not:

- parse command lines or terminal protocols.
- choose completion source priorities or command specifications.
- depend on Swing, IntelliJ Platform, workspace, session, or application modules.
- own product lifecycle beyond explicitly created closeable services/providers.

Hosts remain responsible for invoking environment-specific APIs and for handing snapshot-change callbacks to their UI
thread when necessary.

