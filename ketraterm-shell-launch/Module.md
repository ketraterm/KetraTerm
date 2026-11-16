# Module ketraterm-shell-launch

This library prepares shell launches for hosts that own their processes.
It has no production dependency on workspace, PTY, or Swing.

`TerminalShellLaunch` owns one launch's inventory mailbox and its observation.
The bootstrap prepares arguments, environment values, and reusable startup scripts.
The host owns the script cache and process lifecycle.
Shell integration interprets the output through the session's default parser wiring.

Profile types retain their existing package and JVM names for binary compatibility.
Workspace exports this dependency and uses the same public launch API.
Tests cover preparation, resource cleanup, native shells, and existing workspace behavior.
Native fixtures depend on workspace only in the test configuration.
