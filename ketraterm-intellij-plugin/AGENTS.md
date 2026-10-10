# Terminal IntelliJ Plugin Agent Guide

Read the repository [AGENTS.md](../AGENTS.md) before editing this module.
`ketraterm-intellij-plugin` is an independent nested Gradle build. It owns
IntelliJ-specific product integration and consumes shared KetraTerm libraries.

## Build Ownership

Keep IntelliJ Gradle plugins, repositories, platform dependencies, packaging, and
sandbox configuration in this directory. Its settings include the parent build
with `includeBuild("..")`; keep library dependencies expressed as normal
coordinates with the repository-derived version. The root build must not depend
on this build or acquire IntelliJ tooling.

Use the plugin's own wrapper. Root and plugin toolchains, wrappers, and platform
versions are separate configurations even when they share the same source tree.
The IDE owns the Kotlin/coroutine runtime and Pty4J/JNA native stack. Preserve
runtime exclusions and the archive/ABI checks when changing dependencies.

## Responsibilities

Own IDE extension registrations, tool windows, project-aware launch context,
keymap actions, XML settings, clipboard-client context, navigation, notifications,
and platform disposal. Delegate workspace/session ownership and reusable
terminal behavior to the corresponding modules.

Project terminal services own workspace tabs and sessions. Pane assembly and UI
cleanup run on the EDT; PTY startup, path checks, and completion I/O run off it.
Keep startup rollback and tab/project shutdown idempotent, including failures and
cancellation. Pane disposal must release its own resources without taking
ownership of a borrowed session.

Capture the owning IDE client for clipboard operations. Preserve client identity,
readiness, ordering, session deadlines, and disposal checks across asynchronous
work. Never fall back to another client's clipboard or ambient parser-thread
context. Apply terminal-output permission changes through the session policy.

## Boundary

Do not parse terminal protocols, mutate core internals, implement reusable
painting/selection/input behavior, encode terminal bytes, or own PTY pumping.
Move reusable fixes to ui-swing, ui-swing-host, workspace, session, PTY,
completion, completion-host, or completion-persistence as appropriate. Keep all
IntelliJ imports out of those modules.

Do not create parser, core, renderer, PTY, transport, or input implementation
packages here. Read [Module.md](Module.md) for package and dependency context.

## Testing

Prefer plain JVM tests for independent policy and transformation logic. Use
IntelliJ fixtures for platform registration, settings persistence, client identity,
keymaps, and disposal contracts. Keep timing and lifecycle tests deterministic;
avoid real PTYs or visible IDE windows unless the contract requires them.

From this directory, use `./gradlew spotlessApply`, then focused `test` tasks or
`./gradlew test`. Use `./gradlew check` for archive validation and
`./gradlew verifyPlugin` after platform or dependency changes. On Windows, use
`./gradlew.bat`. Fixture tests may require a display server; see the
[README](README.md#build). Root `test` does not run this independent build.
