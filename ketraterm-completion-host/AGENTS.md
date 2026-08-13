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
- cache merged or prefix-filtered request results or own completion jobs.

A direct scanner may retain one immutable raw directory snapshot when it has
an authoritative filesystem version key. That snapshot is replaced only after
a complete, successful, version-stable scan; prefix filtering remains per request.

Hosts remain responsible for invoking environment-specific APIs. The Swing terminal owns request replacement and
cancellation; the merged engine owns source parallelism.

