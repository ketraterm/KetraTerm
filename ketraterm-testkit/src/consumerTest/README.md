# Published library consumers

Run from the repository root with JDK 25:

```text
./gradlew :ketraterm-testkit:publishedConsumerTest
```

This verification is also part of `:ketraterm-testkit:check` and the test CI
matrix. Each external project declares just one KetraTerm dependency: parser,
host or completion. Kotlin and Java sources compile and execute public APIs;
the smoke programs verify parsed/grid text, collected completion results and
packaged Kotlin metadata.

The root build stages the existing Maven publications' generated POM/module
metadata and main jars into `build/library-consumer-repository`. It includes
their required protocol, core and render-api artifacts. Release signing,
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
