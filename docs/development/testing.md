# Test organization and execution

Tests belong to the module that owns the behavior. A feature name does not create
a new CI job or execution framework. The shared Gradle convention in
[`gradle/test-suites.gradle.kts`](../../gradle/test-suites.gradle.kts) selects suites
by JUnit tags and owns JVM settings, reports, and display serialization.

## Execution suites

| Task | Selection | Environment |
| --- | --- | --- |
| `test` | Excludes `native` and `desktop` | Headless JVM. Unit tests, in-process integration tests, controlled connectors, filesystem fixtures, and offscreen Swing rendering. |
| `nativeTest` | `@Tag("native")` | Headless JVM with installed external tools, real shells, or PTYs. |
| `desktopTest` | `@Tag("desktop")` | Real windows, focus, and platform desktop services on an interactive or virtual display. Can use controlled connectors or live PTYs. |

Use exactly one environment tag. A window test that also starts a shell belongs
to `desktop`, not both suites. Using Swing components or the EDT alone does not
make a test a desktop test; querying native Toolkit services can. Spawning the
current JVM for deterministic isolation does not by itself require an external-tool suite.

The tags describe execution requirements; unit versus integration describes the
behavioral boundary. Tests integrating several modules through fakes still run in
`test`. Existing dedicated publication, consumer, differential, and IDE-plugin
tasks retain their own toolchains and validation contracts.

```shell
./gradlew test                       # Ordinary local verification; no windows or native shell tests
./gradlew nativeTest                 # All modules' native process suites
./gradlew desktopTest                # All modules' real-window suites
./gradlew :ketraterm-app:desktopTest  # One module
./gradlew test nativeTest desktopTest --continue
```

On Linux, wrap desktop runs with `xvfb-run -a`. On Windows use `gradlew.bat`.
Selecting a native/desktop task is the local opt-in; `check` does not unexpectedly
open windows. Direct IDE execution of a desktop test likewise requires a display.
No additional property is needed to enable PTY fixtures.

Each task writes its own `build/test-results/<task>/` XML and
`build/reports/tests/<task>/` HTML. Running another suite cannot replace those
results. Modules without matching tests are valid empty participants in the root
aggregate tasks.

Native and desktop tasks always execute when requested and do not use the build
cache: installed tools, shell state, and desktop behavior can change without a
source change. Compilation and ordinary tests retain Gradle's incremental behavior.
Fixtures can write diagnostics under `terminal.test.artifactsDirectory`, supplied
by these tasks as `build/test-artifacts/<task>/`. Use a feature/scenario subdirectory;
CI retains the common directory without needing a new upload rule for each feature.

## Test and fixture structure

- Use descriptive behavior names for methods; keep expected outcomes in the tests.
- Name new dedicated classes `*NativeTest` or `*DesktopTest` when that requirement
  applies to the whole class. `*IntegrationTest` describes a behavioral boundary,
  not an automatic requirement for processes or windows. Tags, not filename globs,
  determine execution.
- Prefer a class-level tag for a cohesive environment-dependent suite. Method-level
  tags are supported for existing mixed fixtures; do not duplicate substantial setup
  merely to move two methods into another file.
- Keep helpers beside their owning tests. A feature fixture may own its real
  components, session, event waits, and teardown. Promote a helper only when another
  scenario actually needs the same behavior; do not start a generic application-test
  framework in anticipation of future features.
- Test fresh sessions independently. Dispose windows on the EDT, close transports,
  cancel background work, and preserve the original failure if cleanup also fails.
- Use controlled clocks/dispatchers or event handshakes. Deadlines detect a hang;
  sleeps and retries must not establish correctness or hide failures.

Desktop test processes are serialized across modules through one Gradle shared
display service, including under `--parallel`. JUnit and Gradle fork parallelism
are disabled within those suites. Focus-sensitive tests must not compete for the
same desktop. Avoid interacting with their windows during a run.

## CI

The standard [Test Suite workflow](../../.github/workflows/tests.yml) runs all three
suites in each OS job. Shell/display setup is platform-level; no job or class-name
filter is added for suggestions, settings, tabs, or future application features.
Reports are retained on success and failure. Existing packaging/consumer and IDE
checks remain in that workflow; long differential campaigns retain their separate
campaign workflow.

The shell matrix is an environment contract. CI passes
`-Dterminal.test.requiredShells=...` to require the installed shell profiles used
by the desktop shell scenarios. Missing required shells or discovery prerequisites
fail assertions rather than skip. Fish's live-alias scenario remains unsupported;
its editing and directory scenarios still run. Unrequired host-specific profiles
may skip with an explicit reason. Unknown required shell names are errors.

WSL and Ubuntu launcher tests need a configured Windows host; hosted CI does not
claim that coverage. Example from the repository root:

```powershell
./gradlew.bat :ketraterm-app:desktopTest --tests '*StandaloneSuggestionDesktopTest' '-Dterminal.pty.wsl.distribution=Ubuntu' '-Dterminal.test.requiredShells=WINDOWS_POWERSHELL,GIT_BASH,WSL_BASH,WSL_ZSH'
```

The current suggestion scenarios drive component key handlers with live shells;
they do not verify OS keyboard injection or packaged application installation.
Such tests should use the same desktop execution contract when added, with their
actual launch and input mechanisms documented in their owning fixture.
