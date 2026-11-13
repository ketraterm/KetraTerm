# KetraTerm standalone application

The standalone desktop application combines KetraTerm's Swing terminal view,
local PTY sessions, tabs and split panes, settings, and shell integration. This
module is the desktop product; applications embedding the library should start
with [ketraterm-swing](../ketraterm-swing/README.md) and the
[Swing terminal guide](../ketraterm-ui-swing/README.md).

## Run from source

Follow the [development JDK setup](../README.md#development) and use the
repository's Gradle wrapper. From the repository root:

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

New Windows configurations prefer discovered modern PowerShell (`pwsh.exe`),
with Windows PowerShell as fallback. Saved shell choices remain effective.

The standard application-plugin tasks `:ketraterm-app:installDist`,
`:ketraterm-app:distZip`, and `:ketraterm-app:distTar` produce JVM distributions.
Native packaging support stages dependencies with
`:ketraterm-app:prepareJpackageInput`; that task does not build an installer.

## Configure

Use the application's settings dialog to change appearance, shell launch,
and terminal permissions. Settings are saved before publication to
open panes. Initial process and buffer settings apply when a new terminal is
created.

The first window fits the configured grid to the monitor's usable area, preserving
its requested size when it fits. Saved dimensions are unchanged. New tabs and split panes synchronize
their installed viewport dimensions with the session before shell output starts.

Unsuccessful or unknown-status exits and transport failures retain their output
with a closeable status banner. Successful exits close normally. Retained output
remains scrollable, selectable, copyable, and searchable.

For manual edits, see the [configuration reference](docs/profile-config-toml.md)
for file locations, recognized keys, defaults, and recovery behavior. Restart the
application after editing the file directly. The IntelliJ plugin has its own
settings and does not use this file.

Suggestions are enabled by default. The **Suggestions** settings page controls
inline previews, popup mode (**Off**, **On request**, or **Automatic**), shortcuts,
and learning persistence. Add shortcuts by pressing the keys; click a shortcut
chip to remove it. Removing Enter from **Accept selected popup** keeps Enter
available to the shell.
Ctrl+Space requests suggestions when the popup surface is enabled, even when
automatic popups are disabled. Without
an editable shell context, it passes through to the running application.
See [completion availability](../docs/features/completion.md) for shell requirements.

Inline suggestions are opt-in on the same page. They preview the top ranked
completion from the popup's source pipeline, including paths, command
specifications, shell results, and learning. Inline and popup use one request and
debounce, and update together. While that candidate appears inline, the popup starts with
the alternatives, so one Down selects the next completion. Inline also works with popups disabled.
See the [inline contract](../docs/features/completion.md#inline-suffix-presentation)
for append-only eligibility, dismissal, and shell-prediction behavior.

Native local profiles receive a small `ketra` companion command:

```shell
ketra version
ketra info
ketra config
```

`config` opens the active configuration file using the platform helper's editor
selection. WSL and Ubuntu launcher profiles do not receive the native helper or
native configuration paths. Helpers are installed in a private, randomly named
temporary directory with owner-only access and are removed on normal JVM exit.

<a id="maintainer-entry-points"></a>

## Further reading

- [Desktop user guide](docs/user-guide.md)
- [Module structure and dependencies](Module.md)
- [Application changelog](CHANGELOG.md)
- [Library changelog](../CHANGELOG.md)
