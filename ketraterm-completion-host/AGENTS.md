# Terminal Completion Host Support Agent Guide

`ketraterm-completion-host` owns host-neutral suspending filesystem support shared by standalone and IDE products.

## Boundary

This module may:

- expose direct suspending providers.
- resolve explicitly local filesystem completion paths.
- perform bounded local directory scans outside UI threads.

This module must not:

- parse command lines or terminal protocols.
- choose completion source priorities or command specifications.
- depend on Swing, IntelliJ Platform, workspace, session, or application modules.
- cache request results or own completion jobs.

Hosts remain responsible for invoking environment-specific APIs. The Swing terminal owns request replacement and
cancellation; the merged engine owns source parallelism.

