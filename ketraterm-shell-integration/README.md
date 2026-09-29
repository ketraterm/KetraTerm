# Optional shell integration

`ketraterm-shell-integration` supplies KetraTerm's OSC 7/133 implementation.
Select it explicitly when launching a shell with compatible hooks:

```kotlin
val session = TerminalSession.create(
    terminal = terminal,
    connector = connector,
    shellIntegration = OscShellIntegration,
)
```

The session owns synchronization and observation lifetime. Each factory call
creates a separate bounded command timeline and prompt tracker. Command editing
is reconstructed from terminal cells only when requested; revision observers
share session-owned tracking that stops when the last observer leaves.

Hosts using their own shell integration depend on `ketraterm-session` and supply
its neutral integration contract. They do not need this module or OSC markers.
Shell launch-profile adaptation belongs to the launching host/workspace.
