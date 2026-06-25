# Terminal IntelliJ Plugin Agent Guide

`jvterm-intellij-plugin` owns the IntelliJ Platform host integration for
JvTerm. It is an independent Gradle build nested in this repository, not a
subproject of the root JvTerm build.

Before editing this build, read the repository root `AGENTS.md` for global
architecture and quality rules, then follow this module guide.

## Build Ownership

Keep IntelliJ-specific Gradle configuration inside this directory:

- `jvterm-intellij-plugin/settings.gradle.kts`
- `jvterm-intellij-plugin/build.gradle.kts`
- `jvterm-intellij-plugin/gradle.properties`
- `jvterm-intellij-plugin/gradle/libs.versions.toml`

Do not add IntelliJ Platform Gradle plugins, IntelliJ repositories, or
IntelliJ dependency-resolution settings to the root `settings.gradle.kts` or
root `build.gradle.kts`. The root build must remain focused on terminal
libraries, the standalone app, tests, and benchmarks.

When the plugin needs local JvTerm sources, prefer a composite-build setup from
this plugin build, for example `includeBuild("..")`, with dependencies declared
using normal JvTerm coordinates. Do not make the root build depend on the
plugin build.

## Responsibilities

This build may:

- register IntelliJ Platform extensions, actions, services, and tool windows.
- create and dispose IDE-hosted terminal views.
- bind `SwingTerminal` to `TerminalSession`.
- adapt IntelliJ clipboard, browser, notification, settings, and dispatcher
  services to `jvterm-ui-swing` host interfaces.
- choose project-aware launch profiles and working directories.
- coordinate IDE disposal with workspace/session shutdown.
- configure plugin verification, sandbox runs, signing, and publishing.

## Boundary

This build must not:

- parse terminal output protocols.
- mutate terminal core internals.
- implement reusable Swing painting, selection, viewport, or input behavior.
- encode host-bound terminal input bytes directly.
- own PTY stream pumping or process primitives.
- introduce IntelliJ Platform dependencies into reusable JvTerm modules.
- require the root JvTerm Gradle build to apply IntelliJ Platform plugins.
- duplicate standalone app chrome, settings, or tab-management behavior unless
  the behavior is IDE-specific.

Reusable fixes discovered while building the plugin belong in the owning
module: rendering and input in `jvterm-ui-swing`, workspace state in
`jvterm-workspace`, session synchronization in `jvterm-session`, and local PTY
lifecycle in `jvterm-pty`.

## Package Layout

Use narrow packages that describe the IDE adapter boundary:

- `io.github.ketraterm.intellij`: plugin entry points and extension classes.
- `io.github.ketraterm.intellij.ui`: IntelliJ tool-window and Swing host assembly.
- `io.github.ketraterm.intellij.settings`: IDE settings bridges.
- `io.github.ketraterm.intellij.services`: project/application services and
  disposal coordination.

Do not create parser, core, renderer, PTY, transport, or input implementation
packages in this build.

## Testing

Prefer tests that validate host wiring and lifecycle decisions without starting
a real PTY or requiring a visible IDE window. Use IntelliJ Platform test
fixtures only for plugin-facing contracts that cannot be verified as plain JVM
logic.

Run plugin checks from the repository root:

```text
./gradlew -p jvterm-intellij-plugin test
```

Or from this directory:

```text
./gradlew test
```
