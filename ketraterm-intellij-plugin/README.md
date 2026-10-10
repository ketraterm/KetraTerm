# KetraTerm IntelliJ Plugin

KetraTerm hosts local terminal tabs in an IntelliJ tool window. It uses the shared
KetraTerm terminal libraries and adapts them to IDE settings, project directories,
clipboard access, navigation, notifications, and disposal.

## Status

The build targets IntelliJ IDEA 2026.2 and declares minimum platform build `262`.
The plugin requires the Git and Gradle bundled plugins declared in its
[manifest](src/main/resources/META-INF/plugin.xml). Its JVM classes target Java
25, so the IDE runtime must support that bytecode level.

The plugin is an independent Gradle build. The repository root builds the
libraries and standalone application; this directory owns IDE packaging and
sandbox tasks.

## Install and use

Build a distribution with `buildPlugin` as described below. In the target IDE,
open **Settings → Plugins → Install Plugin from Disk**, select the ZIP from
`build/distributions/`, and restart when prompted.

Open the **KetraTerm** tool window to create terminal tabs. The new-terminal menu
also offers discovered shell profiles. **Open in KetraTerm** opens a terminal in
a selected project directory or the directory containing a selected file.

Configure **Settings → Tools → KetraTerm** for the shell path, launch directory,
environment, typography, cursor, scrollback, input behavior, and terminal-output
permissions. A blank launch directory uses the project root, falling back to the
user home when no project path is available. The project JDK can be added to the
shell environment through the corresponding setting.

Completion is disabled by default and its settings controls are hidden; see
[completion availability](../docs/features/completion.md#availability).

The **Project** settings page accepts a single startup command. The command is
submitted once after shell readiness, including in restored tabs; typing before
readiness cancels it. Shell support and readiness behavior follow the shared
[workspace startup contract](../ketraterm-workspace/README.md).

Tabs restore their names, shell-profile identities, and local working directories
using current settings. They start fresh shell processes when shown; restoration
does not recover prior processes or terminal contents. Automatic initial startup
and restoration wait for IntelliJ project trust.

Terminal-output clipboard reads ask for permission by default in new settings;
clipboard writes and title changes are allowed by default. Saved permissions are
preserved. These policies apply to the whole session, including output from SSH
commands. Ordinary copy and paste remain user actions. Clipboard requests are
bound to the owning IDE client and terminal, and pending reads expire or cancel
when their session or owner closes.

## Build

Use JDK 25 and this directory's Gradle wrapper. The wrapper and IDE target are
configured independently from the root library build. From the repository root:

```text
cd ketraterm-intellij-plugin
./gradlew test
./gradlew runIde
./gradlew buildPlugin
./gradlew verifyPlugin
```

On Windows, use `./gradlew.bat`. `runIde` starts an IDE sandbox with the plugin
installed. `buildPlugin` writes the installable ZIP to `build/distributions/`.
IDE dependencies and verification tooling may be downloaded on the first run.

Use `./gradlew spotlessApply` before submitting Kotlin or Gradle changes.
`./gradlew check` also builds the distribution and checks that it contains no
Kotlin standard-library JARs. Plugin tests run independently of root `test`.
Tests that use IntelliJ fixtures may need a display server; the repository CI
runs them under Xvfb on Linux.

### Releases

The version comes from the repository [VERSION](../VERSION) file. Development
builds append `-SNAPSHOT`; setting environment variable `RELEASE=true` uses the
exact version. For example, from this directory in a POSIX shell:

```sh
RELEASE=true ./gradlew buildPlugin verifyPlugin
```

On PowerShell, set `$env:RELEASE = 'true'` before invoking the wrapper. Release
packaging does not publish the plugin. Maintainers use `publishPlugin` with
Marketplace credentials supplied to the IntelliJ Platform Gradle plugin.
The configured `pluginPublishChannel` defaults to `default` and can be overridden
with `-PpluginPublishChannel=...`. Release notes come from the first section of
[CHANGELOG.md](CHANGELOG.md).

## Architecture

See [Module.md](Module.md) for IDE services and assembly, and
[repository architecture](../ARCHITECTURE.md) for shared module boundaries.

<a id="local-source-wiring"></a>

Local development uses a composite build; no Maven publication is required.
See [build composition and dependencies](Module.md#dependencies-and-local-build).

## Documentation

- [CHANGELOG.md](CHANGELOG.md): plugin release history.
- [plugin.xml](src/main/resources/META-INF/plugin.xml): registrations and required IDE plugins.
