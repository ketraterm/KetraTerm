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
./gradlew :nativeTest                # Declared modules' native process suites
./gradlew :desktopTest               # Declared modules' real-window suites
./gradlew :ketraterm-app:desktopTest  # One module
./gradlew test :nativeTest :desktopTest --continue
```

On Linux, wrap desktop runs with `xvfb-run -a`. On Windows use `gradlew.bat`.
Selecting a native/desktop task is the local opt-in; `check` does not unexpectedly
open windows. Direct IDE execution of a desktop test likewise requires a display.
No additional property is needed to enable PTY fixtures.

Each task writes its own `build/test-results/<task>/` XML and
`build/reports/tests/<task>/` HTML. Running another suite cannot replace those
results. Declare modules with environment-dependent tests in
[`gradle/test-environments.properties`](../../gradle/test-environments.properties).
Root aggregates depend only on those owners; other modules' environment tasks
skip without launching an empty test JVM. CI checks that every tagged source has
a declared owner.

Native and desktop tasks always execute when requested and do not use the build
cache: installed tools, shell state, and desktop behavior can change without a
source change. Compilation and ordinary tests use Gradle's build cache; Gradle
tracks their source, runtime classpath, and other declared inputs.
Fixtures can write diagnostics under `terminal.test.artifactsDirectory`, supplied
by these tasks as `build/test-artifacts/<task>/`. Use a feature/scenario subdirectory;
CI retains the common directory without needing a new upload rule for each feature.

Native and desktop tasks log each test's start and outcome, including standard
output and error. Each test invocation and lifecycle method has a default
five-minute JUnit timeout on its existing execution thread. Before interrupting a
timed-out invocation, JUnit prints all JVM thread stacks to standard output so the
build log identifies the active test and blocked threads. Blocking code may ignore
interruption; this diagnostic deadline does not guarantee process termination or
cover Gradle and test-worker startup or shutdown. Fixture-specific waits should
still use shorter deadlines and controlled synchronization.

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

The [Test Suite workflow](../../.github/workflows/tests.yml) plans independent
validation groups, then runs the selected groups in parallel:

| Group | Validation |
| --- | --- |
| Headless | Linux JVM tests, formatting, ABI, and native version metadata. |
| Platform | Linux native/desktop tests; Windows and macOS JVM/native/desktop tests. Real-window suites remain serialized within each display. |
| Consumers | Published Kotlin/Java clients, retained clients, and publication metadata. Linux on selective PRs; all three operating systems on full runs. |
| IntelliJ plugin | Formatting and tests in the independent plugin build. |
| Benchmarks | JMH compilation and packaging. |
| Website | Documentation, website tests, formatting, build, and links. |
| Differential | Bounded terminal-state smoke campaigns. Long campaigns retain their separate scheduled workflow. |

PRs and merge groups select affected groups conservatively. Documentation changes
run website checks; test changes run JVM tests and the module's declared environment
suites; production changes also run platform and relevant integration groups.
Gradle owns downstream test invalidation through runtime classpaths: CI does not
maintain a second module dependency graph. Build/toolchain/CI changes, unknown
paths, empty diffs, and failed diff discovery request all groups. Pushes to
`master`/`main`, daily schedules, manual runs, and reusable callers default to full
validation. Release publication waits for full validation of the exact tagged
revision, including all three consumer platforms. Reports are retained on success
and failure.

Require **CI gate** in branch protection. It succeeds only when planning and every
selected group succeed; only deliberately unselected groups may skip. When
adopting this workflow, replace the old OS test and Code Style required checks
with this gate so deliberately skipped groups cannot leave a PR waiting forever.

The shared JVM setup restores Gradle dependencies, compiled outputs, and cached
headless test results. Native/desktop results are never cached. Trusted default
branch runs populate the cache; PRs use setup-gradle's default read-only policy.
Optionally configure the Actions secret `GRADLE_ENCRYPTION_KEY` to persist Gradle
configuration-cache entries, following the
[setup-gradle encryption instructions](https://github.com/gradle/actions/blob/v4/docs/setup-gradle.md#saving-configuration-cache-data).
Without that secret, including on fork PRs, verification still runs but configuration
cache entries are not persisted between runners.

Validate CI selection, gating, and release automation locally with:

```shell
node --test .github/scripts/*.test.mjs
```

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
The suggestion scenarios exercise automatic popups through the product settings,
including PowerShell profiles with PSReadLine.
Such tests should use the same desktop execution contract when added, with their
actual launch and input mechanisms documented in their owning fixture.
