# Module jvterm-intellij-plugin

## JvTerm IntelliJ Plugin

The `jvterm-intellij-plugin` build is the IntelliJ Platform host for JvTerm.
It is a product integration build, not a terminal implementation module.

This build stays independent from the root JvTerm Gradle build so IntelliJ
Platform plugin tooling, repositories, sandbox tasks, verification, signing,
and publishing do not leak into the terminal library build.

## Role

The plugin adapts reusable JvTerm modules to IntelliJ IDE services:

- terminal rendering and input through `jvterm-ui-swing`.
- workspace and session state through `jvterm-workspace` and
  `jvterm-session`.
- local process lifecycle through `jvterm-pty`.
- IDE lifecycle, actions, settings, notifications, tool windows, and disposal
  through IntelliJ Platform APIs.

## Boundary

The plugin must not parse terminal protocols, mutate core terminal state,
implement reusable renderer behavior, encode host-bound input bytes directly,
or add IntelliJ dependencies to reusable JvTerm modules.

## Current Scope

This build currently contains plugin scaffolding and documentation. Runtime IDE
features should be added in small slices with focused tests and clear
ownership boundaries.
