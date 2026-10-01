# Published library consumers

Run from the repository root with JDK 25:

```text
./gradlew :ketraterm-testkit:publishedConsumerTest
```

This verification is also part of `:ketraterm-testkit:check` and the test CI
matrix. Each external project declares just one KetraTerm dependency: parser,
host, completion or ui-swing. Kotlin and Java sources compile and execute public
APIs; the smoke programs verify parsed/grid text, collected completion results,
Swing host integration and packaged Kotlin metadata.

The Swing consumer creates and binds a public `SwingTerminal` on the EDT to a
host-owned session and in-memory connector. A suspending detector supplies neutral
presentation and activation metadata. Public hover and activation callbacks verify
that discovery results were installed, including a provider configuration refresh
without terminal output. Unbinding cancels the view's provider subscription;
unbinding and disposal leave session/transport ownership with the host. Callback
handshakes coordinate the headless smoke; it uses no timing assertions, private
reflection, internal constructors or extra library dependencies. The Java smoke
uses functional actions and public host wiring, without adapting coroutines.

The root build stages the existing Maven publications' generated POM/module
metadata and main jars into `build/library-consumer-repository`. It includes
their transitive KetraTerm artifacts. Release signing,
remote publishing, Maven Local and source-project substitution are not used.
Sources and documentation variants are outside this runtime-consumer check.

The fixture is copied into testkit's build directory and run twice, with
separate compiler outputs: Gradle module metadata, then POM-only resolution
with Gradle metadata redirection disabled. KetraTerm coordinates resolve
exclusively from the staged repository; third-party dependencies resolve from
Maven Central. The repository's Gradle wrapper, Kotlin plugin version and JDK
25 toolchain are reused.

Existing `TerminalLibraryConsumerCompilationTest` regressions separately check
Java compilation against exported project API variants without the fixture's
Kotlin plugin or testkit classpath. ABI upgrade checks belong to G01.
