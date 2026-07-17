# KetraTerm IntelliJ Plugin

`ketraterm-intellij-plugin` is the IntelliJ Platform host build for KetraTerm.

This build is intentionally independent from the root KetraTerm Gradle build. The
root build owns the terminal libraries, standalone app, tests, and benchmarks.
This build owns IntelliJ Platform plugin packaging, sandbox runs, plugin
verification, IDE services, signing, and Marketplace publishing.

## Status

The plugin is the IntelliJ host for KetraTerm. It opens local terminal tabs in
a bottom tool window, uses KetraTerm's reusable Swing terminal and
workspace/session stack, and exposes IDE-native settings for startup, rendering,
input, and security policy.

Current intent:

- keep IntelliJ Gradle plugins and repositories inside this build.
- reuse KetraTerm modules instead of copying terminal logic into the plugin.
- keep the root `settings.gradle.kts` and root `build.gradle.kts` free of
  IntelliJ Platform configuration.
- use the repository `VERSION` file for the plugin and KetraTerm library
  dependency version. Local development builds append `-SNAPSHOT`; release
  builds use the exact repository version when `RELEASE=true`.
- publish releases to the Marketplace default channel unless
  `pluginPublishChannel` is overridden.

## Build

From this directory:

```text
./gradlew test
./gradlew runIde
./gradlew verifyPlugin
./gradlew buildPlugin
```

From the repository root:

```text
./gradlew -p ketraterm-intellij-plugin test
./gradlew -p ketraterm-intellij-plugin runIde
./gradlew -p ketraterm-intellij-plugin verifyPlugin
./gradlew -p ketraterm-intellij-plugin buildPlugin
```

Release build:

```text
RELEASE=true ./gradlew -p ketraterm-intellij-plugin buildPlugin verifyPlugin
```

Marketplace publishing uses the IntelliJ Platform Gradle Plugin defaults for
`PUBLISH_TOKEN` and publishes to `pluginPublishChannel` from
`gradle.properties`.

```text
RELEASE=true PUBLISH_TOKEN=... ./gradlew -p ketraterm-intellij-plugin publishPlugin
```

## Architecture

The plugin is a thin IDE host. It may register IntelliJ extensions, actions,
services, tool windows, settings bridges, notifications, and disposal hooks.

Reusable terminal behavior stays in the main KetraTerm modules:

- `ketraterm-completion`: pure command parsing, sources, ranking, and learning.
- `ketraterm-completion-host`: bounded asynchronous directory and value snapshots.
- `ketraterm-completion-persistence`: sanitized, bounded statistics storage.
- `ketraterm-ui-swing`: Swing terminal component, painting, selection, input, and
  viewport behavior.
- `ketraterm-ui-swing-host`: reusable completion-to-Swing request and feedback adapters.
- `ketraterm-workspace`: host-neutral terminal profiles and workspace/session
  state.
- `ketraterm-session`: parser/core/input/transport synchronization.
- `ketraterm-pty`: local PTY process lifecycle.

The plugin must not parse terminal bytes, mutate core grid state, implement
renderer hot paths, encode terminal input bytes directly, or add IntelliJ
Platform dependencies to reusable modules.

## Local Source Wiring

This build uses published-style KetraTerm coordinates and local substitution
through a composite build:

```kotlin
// ketraterm-intellij-plugin/settings.gradle.kts
includeBuild("..")
```

Plugin dependencies should continue to use normal coordinates rather than root
project references:

```kotlin
implementation("io.github.ketraterm:ketraterm-ui-swing")
implementation("io.github.ketraterm:ketraterm-completion-host")
implementation("io.github.ketraterm:ketraterm-completion-persistence")
implementation("io.github.ketraterm:ketraterm-workspace")
```

This keeps IDE plugin development independent while still using local KetraTerm
sources during development.

## Documentation

- `AGENTS.md`: agent and contributor rules for this build.
- `Module.md`: concise module role summary for generated documentation.
- `src/main/resources/META-INF/plugin.xml`: IntelliJ plugin manifest.
