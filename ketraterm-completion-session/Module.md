# Module ketraterm-completion-session

This optional module adapts public terminal session services to the completion
engine. It depends on `ketraterm-session` and `ketraterm-completion`.

`SessionShellCompletionProvider` maps POSIX command inventories and native
PowerShell results into completion candidates. The session owns request admission,
correlation, cancellation, and editing authority. The host owns the session,
source selection, and completion request lifetime.

The module contains no UI, process launcher, filesystem scanner, or product policy.
The Swing host helper retains its existing provider class and delegates here.
The [README](README.md) describes consumer setup and ownership.
