# KetraTerm standalone application

The standalone desktop application combines KetraTerm's Swing terminal view,
local PTY sessions, tabs and split panes, settings, and shell integration. This
module is the desktop product; applications embedding the library should start
with [ketraterm-swing](../ketraterm-swing/README.md) and the
[Swing terminal guide](../ketraterm-ui-swing/README.md).

## Run from source

Use a JDK 25 installation and the repository's Gradle wrapper. From the
repository root:

```shell
./gradlew :ketraterm-app:run
```

On Windows, use `gradlew.bat`. The application task supplies the native-access
JVM option required by the local PTY backend. A desktop session and an installed
shell are required.

The initial terminal uses the configured shell. Arguments supplied to the
application replace that initial launch command, preserving each argument as
part of the process command:

```shell
./gradlew :ketraterm-app:run --args="bash -l"
```

These are process arguments, rather than application options. They affect the
initial terminal; later terminals use the selected profile. The configured
startup directory applies to the initial terminal as well.

The standard application-plugin tasks `:ketraterm-app:installDist`,
`:ketraterm-app:distZip`, and `:ketraterm-app:distTar` produce JVM distributions.
Native packaging support stages dependencies with
`:ketraterm-app:prepareJpackageInput`; that task does not build an installer.

## Configure

Use the application's settings dialog to change appearance, shell launch,
and terminal permissions. Settings are saved before publication to
open panes. Initial process and buffer settings apply when a new terminal is
created.

For manual edits, see the [configuration reference](docs/profile-config-toml.md)
for file locations, recognized keys, defaults, and recovery behavior. Restart the
application after editing the file directly. The IntelliJ plugin has its own
settings and does not use this file.

Completion controls are currently hidden; see [completion availability](../docs/features/completion.md)
for enabling suggestions through the configuration file.

Native local profiles receive a small `ketra` companion command:

```shell
ketra version
ketra info
ketra config
```

`config` opens the active configuration file using the platform helper's editor
selection. WSL and Ubuntu launcher profiles do not receive the native helper or
native configuration paths.

<a id="maintainer-entry-points"></a>

## Further reading

- [Desktop user guide](docs/user-guide.md)
- [Module structure and dependencies](Module.md)
- [Application changelog](CHANGELOG.md)
- [Library changelog](../CHANGELOG.md)
