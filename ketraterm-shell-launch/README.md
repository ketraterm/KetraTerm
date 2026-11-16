# KetraTerm shell launch

Use this optional library when your host owns process creation and needs KetraTerm's shell hooks.
It prepares arguments, environment values, and startup scripts without creating a PTY or workspace.

```kotlin
implementation(platform("io.github.ketraterm:ketraterm-bom:{version}"))
implementation("io.github.ketraterm:ketraterm-shell-launch")
```

Call `io.github.ketraterm.shell.launch.TerminalShellLaunch.prepare(profile)` before process creation.
Launch `launch.profile.command` with `launch.environment(inheritedEnvironment)` through your process host.
`inheritedEnvironment` is the environment selected by your process host.
Create the session with `launch.shellIntegration`, then call `launch.attach(session)` before `session.start`.
Close the launch on startup failure. On disposal, close the process and session before the launch.

The profile types retain the `io.github.ketraterm.workspace` package to preserve the existing profile ABI.
The artifact has no production dependency on workspace or PTY.
The host owns the script cache. Each launch owns its command inventory mailbox.
Use a script directory that the shell can access. This API does not deploy scripts to remote hosts.

See `TerminalShellLaunch` for lifecycle details and [shell support](../docs/features/shells.md) for supported launches.
