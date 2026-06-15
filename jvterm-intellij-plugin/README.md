# JvTerm IntelliJ Plugin

`jvterm-intellij-plugin` is the IntelliJ Platform host build for JvTerm.

This build is intentionally independent from the root JvTerm Gradle build. The
root build owns the terminal libraries, standalone app, tests, and benchmarks.
This build owns IntelliJ Platform plugin packaging, sandbox runs, plugin
verification, IDE services, and future Marketplace publishing.

## Status

The plugin is currently a scaffold. Runtime terminal integration should be
added in small, verifiable slices after the build structure is clean.

Current intent:

- keep IntelliJ Gradle plugins and repositories inside this build.
- reuse JvTerm modules instead of copying terminal logic into the plugin.
- keep the root `settings.gradle.kts` and root `build.gradle.kts` free of
  IntelliJ Platform configuration.
- use a composite build for local development when the plugin begins depending
  on JvTerm modules.

## Build

From this directory:

```text
./gradlew test
./gradlew runIde
./gradlew verifyPlugin
```

From the repository root:

```text
./gradlew -p jvterm-intellij-plugin test
./gradlew -p jvterm-intellij-plugin runIde
./gradlew -p jvterm-intellij-plugin verifyPlugin
```

## Architecture

The plugin is a thin IDE host. It may register IntelliJ extensions, actions,
services, tool windows, settings bridges, notifications, and disposal hooks.

Reusable terminal behavior stays in the main JvTerm modules:

- `jvterm-ui-swing`: Swing terminal component, painting, selection, input, and
  viewport behavior.
- `jvterm-workspace`: host-neutral terminal profiles and workspace/session
  state.
- `jvterm-session`: parser/core/input/transport synchronization.
- `jvterm-pty`: local PTY process lifecycle.

The plugin must not parse terminal bytes, mutate core grid state, implement
renderer hot paths, encode terminal input bytes directly, or add IntelliJ
Platform dependencies to reusable modules.

## Local Source Wiring

When plugin implementation begins, prefer published-style dependencies in this
build and local substitution through a composite build:

```kotlin
// jvterm-intellij-plugin/settings.gradle.kts
includeBuild("..")
```

The plugin dependencies should then use normal coordinates rather than root
project references:

```kotlin
implementation("io.github.jvterm:jvterm-ui-swing")
implementation("io.github.jvterm:jvterm-workspace")
```

This keeps IDE plugin development independent while still using local JvTerm
sources during development.

## Documentation

- `AGENTS.md`: agent and contributor rules for this build.
- `Module.md`: concise module role summary for generated documentation.
- `src/main/resources/META-INF/plugin.xml`: IntelliJ plugin manifest.
